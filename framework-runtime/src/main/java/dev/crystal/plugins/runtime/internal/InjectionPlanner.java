package dev.crystal.plugins.runtime.internal;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.inject.AbstractModule;
import com.google.inject.ConfigurationException;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.InjectionPoint;

/**
 * Computes the explicit bindings of one scope (a plugin, or an application object created through
 * {@code PluginService.create}).
 *
 * <p>Starting from the given classes it walks their injection points and binds:
 * <ul>
 *   <li>{@code Set<R>} for a role {@code R} → the registry's live view;</li>
 *   <li>{@code R} for a role {@code R} → the single active implementation;</li>
 *   <li>any other concrete class reached → an explicit binding in <em>this</em> child injector.</li>
 * </ul>
 * Every scope gets its own injector, built with {@code requireExplicitBindings()}: a type the walk misses
 * fails loudly instead of being bound behind our back.
 *
 * <p>Host services are bound by the host module every injector installs, so they are left alone. For a
 * sub-plugin, a key its parent's injector has bound is delegated to that injector: the sub-plugin gets the
 * parent's very object (the same singleton), while the parent never learns about the sub-plugin.
 */
final class InjectionPlanner {

    private final RoleRegistry registry;

    InjectionPlanner(RoleRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param singletons classes bound as singletons of the scope (role implementations)
     * @param others     classes bound unscoped (the application object being created)
     * @param requester  plugin id for error messages, {@code null} for the application
     * @param host       whether a key is a host service (bound by the host module)
     * @param parent     the enclosing plugin's injector, or {@code null}
     */
    Module plan(Collection<Class<?>> singletons, Collection<Class<?>> others, String requester,
                Predicate<Key<?>> host, Injector parent) {
        Set<Class<?>> concrete = new LinkedHashSet<>();
        Map<Key<?>, Class<?>> roleSets = new LinkedHashMap<>();
        Map<Key<?>, Class<?>> roleSingles = new LinkedHashMap<>();
        Set<Key<?>> delegated = new LinkedHashSet<>();
        Predicate<Key<?>> inParent = parent == null ? key -> false : key -> parent.getExistingBinding(key) != null;

        Deque<Class<?>> work = new ArrayDeque<>(singletons);
        work.addAll(others);
        Set<Class<?>> seen = new HashSet<>();
        while (!work.isEmpty()) {
            Class<?> type = work.pop();
            if (!seen.add(type)) {
                continue;
            }
            concrete.add(type);
            for (Dependency<?> dependency : dependenciesOf(type)) {
                classify(dependency.getKey(), host, inParent, work, roleSets, roleSingles, delegated);
            }
        }

        return new AbstractModule() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            protected void configure() {
                for (Class<?> type : concrete) {
                    if (singletons.contains(type)) {
                        bind(type).in(Scopes.SINGLETON);
                    } else {
                        bind(type);
                    }
                }
                roleSets.forEach((key, role) -> bind((Key) key).toProvider(() -> registry.view(role)));
                roleSingles.forEach((key, role) -> bind((Key) key).toProvider(() -> registry.single(role, requester)));
                delegated.forEach(key -> bind((Key) key).toProvider(() -> parent.getInstance(key)));
            }
        };
    }

    private void classify(Key<?> key, Predicate<Key<?>> host, Predicate<Key<?>> inParent, Deque<Class<?>> work,
                          Map<Key<?>, Class<?>> roleSets, Map<Key<?>, Class<?>> roleSingles, Set<Key<?>> delegated) {
        if (host.test(key) || key.getAnnotationType() != null) {
            // Host services, or qualified keys we cannot guess: Guice resolves or reports them.
            return;
        }
        TypeLiteral<?> literal = key.getTypeLiteral();
        Class<?> raw = literal.getRawType();
        Type type = literal.getType();

        if (isProvider(raw) && type instanceof ParameterizedType p) {
            classify(Key.get(p.getActualTypeArguments()[0]), host, inParent, work, roleSets, roleSingles, delegated);
            return;
        }
        if (raw == Set.class && type instanceof ParameterizedType p
                && p.getActualTypeArguments()[0] instanceof Class<?> element && Roles.isRole(element)) {
            roleSets.put(key, element);
            return;
        }
        if (Roles.isRole(raw)) {
            roleSingles.put(key, raw);
            return;
        }
        if (inParent.test(key)) {
            delegated.add(key);
            return;
        }
        if (isBindableClass(raw)) {
            work.add(raw);
        }
    }

    private static boolean isProvider(Class<?> raw) {
        return raw == com.google.inject.Provider.class || raw == jakarta.inject.Provider.class;
    }

    private static boolean isBindableClass(Class<?> raw) {
        return !raw.isInterface() && !raw.isPrimitive() && !raw.isArray()
                && !Modifier.isAbstract(raw.getModifiers())
                && !raw.getName().startsWith("java.") && !raw.getName().startsWith("com.google.inject.");
    }

    private static Set<Dependency<?>> dependenciesOf(Class<?> type) {
        Set<Dependency<?>> dependencies = new LinkedHashSet<>();
        try {
            dependencies.addAll(InjectionPoint.forConstructorOf(type).getDependencies());
            for (InjectionPoint point : InjectionPoint.forInstanceMethodsAndFields(type)) {
                dependencies.addAll(point.getDependencies());
            }
        } catch (ConfigurationException e) {
            // No usable constructor etc.: leave it to Guice, which reports it with full context.
        }
        return dependencies;
    }
}
