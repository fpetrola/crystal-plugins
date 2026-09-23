package dev.crystal.plugins.runtime.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.pf4j.PluginDependency;
import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Stage;

import dev.crystal.plugins.api.HasLifecycle;
import dev.crystal.plugins.api.Replaces;

/**
 * The Guice half of the mirrored isolation: one child injector per started plugin.
 *
 * <p>PF4J owns the lifecycle and its ordering (dependencies start first, dependents stop first); this
 * class only reacts to it through {@link RolePlugin}: on start it builds the child injector, instantiates
 * the plugin's role implementations, runs {@link HasLifecycle#onStart()} and publishes them; on stop it
 * does the reverse and forgets the injector. The plugin's classloader (PF4J) and injector (Guice) are
 * thus created and dropped together.
 *
 * <p>Each scope is a standalone injector, not a child of a long-lived root: Guice records every key a child
 * binds in all its ancestors ("banned keys"), holding the key, hence the plugin's classes, hence its
 * classloader, until some later lookup in the ancestor happens to clean it up. A standalone injector dies with
 * its plugin, completely. Host services come from the host module that every injector installs.
 *
 * <p>Scopes nest like plugins do: a sub-plugin, a plugin with exactly one required plugin dependency, has the
 * keys its parent's injector bound delegated to that injector, so it sees the objects its parent built (the
 * same singletons, not copies) while the parent holds nothing of it. With several parents there is no single
 * enclosing scope; such a plugin reaches other plugins' objects through roles, like everyone else. Either way
 * dependents stop first, so a scope never outlives the one it is nested in.
 */
public final class PluginScopes {

    private static final Logger log = LoggerFactory.getLogger(PluginScopes.class);

    private record Scope(Injector injector, List<Object> instances) {
    }

    private final Module host;
    private final Set<Key<?>> hostKeys;
    private final RoleRegistry registry;
    private final InjectionPlanner planner;
    private final Function<String, Set<String>> extensionClassNames;
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();

    /** @param exposed host services, injectable everywhere */
    public PluginScopes(Map<Class<?>, Object> exposed, RoleRegistry registry,
                        Function<String, Set<String>> extensionClassNames) {
        this.hostKeys = exposed.keySet().stream().map(Key::get).collect(Collectors.toUnmodifiableSet());
        this.host = new AbstractModule() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            protected void configure() {
                // No just-in-time bindings: the planner binds everything explicitly, a miss fails loudly.
                binder().requireExplicitBindings();
                // A provider, not toInstance: Guice would inject the host's objects again in every injector.
                exposed.forEach((type, instance) -> bind((Class) type).toProvider(() -> instance));
            }
        };
        this.registry = registry;
        this.extensionClassNames = extensionClassNames;
        this.planner = new InjectionPlanner(registry);
    }

    void activate(PluginWrapper plugin) {
        String id = plugin.getPluginId();
        ClassLoader loader = plugin.getPluginClassLoader();

        List<Class<?>> implementations = new ArrayList<>();
        // PF4J keeps the index in a HashSet; sort so activation order is reproducible.
        for (String className : new TreeSet<>(extensionClassNames.apply(id))) {
            Class<?> type;
            try {
                type = loader.loadClass(className);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new PluginRuntimeException(e, "Plugin '{}': cannot load extension class {}", id, className);
            }
            if (Roles.of(type).isEmpty()) {
                String hint = Roles.foreignRoleAnnotationHint(type);
                log.warn("Plugin '{}': {} is indexed but implements no @RoleInterface{}", id, className,
                        hint == null ? "" : " (" + hint + ")");
                continue;
            }
            implementations.add(type);
        }

        Injector parent = enclosing(plugin);
        RoleRegistry.Built<Injector> built = registry.build(() -> {
            // Singletons are eager (production stage): they are all built, and their fixed references recorded, here.
            Injector own = Guice.createInjector(Stage.PRODUCTION, host,
                    planner.plan(implementations, List.of(), id, hostKeys::contains, parent));
            implementations.forEach(own::getInstance);
            return own;
        });
        Injector injector = built.value();
        registry.heldByPlugin(id, built.owners());

        List<Object> instances = new ArrayList<>(implementations.size());
        List<Contribution> contributions = new ArrayList<>(implementations.size());
        String version = plugin.getDescriptor().getVersion();
        for (Class<?> type : implementations) {
            Object instance = injector.getInstance(type);
            instances.add(instance);
            Replaces replaces = type.getAnnotation(Replaces.class);
            contributions.add(new Contribution(id, version, instance, Set.copyOf(Roles.of(type)),
                    replaces == null ? List.of() : List.of(replaces.value())));
        }

        List<HasLifecycle> started = new ArrayList<>();
        for (Object instance : instances) {
            if (instance instanceof HasLifecycle lifecycle) {
                try {
                    lifecycle.onStart();
                    started.add(lifecycle);
                } catch (Exception e) {
                    stopAll(id, started);
                    throw new PluginRuntimeException(e, "Plugin '{}': onStart() of {} failed", id,
                            instance.getClass().getName());
                }
            }
        }

        scopes.put(id, new Scope(injector, instances));
        registry.publish(contributions);
        log.debug("Plugin '{}' active with {} role implementation(s)", id, instances.size());
    }

    void deactivate(PluginWrapper plugin) {
        String id = plugin.getPluginId();
        Scope scope = scopes.remove(id);
        registry.withdraw(id, plugin.getPluginClassLoader());
        if (scope == null) {
            return;
        }
        List<HasLifecycle> lifecycles = new ArrayList<>();
        for (Object instance : scope.instances()) {
            if (instance instanceof HasLifecycle lifecycle) {
                lifecycles.add(lifecycle);
            }
        }
        stopAll(id, lifecycles);
    }

    /** The injector a plugin's scope nests in: its only required plugin dependency's, or none. */
    private Injector enclosing(PluginWrapper plugin) {
        List<String> required = plugin.getDescriptor().getDependencies().stream()
                .filter(d -> !d.isOptional()).map(PluginDependency::getPluginId).toList();
        if (required.size() == 1) {
            Scope parent = scopes.get(required.get(0));
            if (parent != null) {
                return parent.injector();
            }
        }
        return null;
    }

    /**
     * Instantiates an application object; its {@code Set<Role>} and {@code Provider<Role>} dependencies are live,
     * a single role is fixed, and recorded as held by the object until it is garbage collected.
     */
    public <T> T create(Class<T> type) {
        return building(() -> Guice.createInjector(Stage.PRODUCTION, host,
                planner.plan(List.of(), List.of(type), null, hostKeys::contains, null)).getInstance(type));
    }

    /** See {@code PluginService.preferred}. */
    public <T> T preferred(Class<T> role) {
        return registry.single(role, null);
    }

    /** See {@code PluginService.snapshot}. */
    public <T> List<T> snapshot(Class<T> role) {
        return registry.fixed(role);
    }

    /** See {@code PluginService.building}. */
    public <T> T building(Supplier<T> build) {
        RoleRegistry.Built<T> built = registry.build(build);
        if (built.value() != null) {
            registry.heldByApplication(built.value(), built.owners());
        }
        return built.value();
    }

    /** Calls {@link HasLifecycle#onStop()} in reverse order; failures are logged, never propagated. */
    private static void stopAll(String id, List<HasLifecycle> lifecycles) {
        for (int i = lifecycles.size() - 1; i >= 0; i--) {
            HasLifecycle lifecycle = lifecycles.get(i);
            try {
                lifecycle.onStop();
            } catch (Exception | LinkageError e) {
                log.error("Plugin '{}': onStop() of {} failed", id, lifecycle.getClass().getName(), e);
            }
        }
    }
}
