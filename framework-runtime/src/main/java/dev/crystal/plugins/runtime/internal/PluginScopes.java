package dev.crystal.plugins.runtime.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.pf4j.PluginRuntimeException;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Injector;

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
 */
public final class PluginScopes {

    private static final Logger log = LoggerFactory.getLogger(PluginScopes.class);

    private record Scope(Injector injector, List<Object> instances) {
    }

    private final Injector root;
    private final RoleRegistry registry;
    private final InjectionPlanner planner;
    private final Function<String, Set<String>> extensionClassNames;
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();

    public PluginScopes(Injector root, RoleRegistry registry, Function<String, Set<String>> extensionClassNames) {
        this.root = root;
        this.registry = registry;
        this.extensionClassNames = extensionClassNames;
        this.planner = new InjectionPlanner(registry, key -> root.getExistingBinding(key) != null);
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

        Injector injector = root.createChildInjector(planner.plan(implementations, List.of(), id));
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

    /** Instantiates an application object; its role dependencies are live views. */
    public <T> T create(Class<T> type) {
        Injector injector = root.createChildInjector(planner.plan(List.of(), List.of(type), null));
        return injector.getInstance(type);
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
