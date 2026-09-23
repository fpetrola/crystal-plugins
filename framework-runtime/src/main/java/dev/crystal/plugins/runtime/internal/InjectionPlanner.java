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
 * The last point is what keeps isolation mirrored: Guice would otherwise create just-in-time bindings in
 * the root injector whenever it can, and a root binding to a plugin class pins that plugin's classloader
 * forever. The root injector is built with {@code requireExplicitBindings()}, so a missed type fails loudly
 * instead of leaking.
 */
final class InjectionPlanner {

    private final RoleRegistry registry;
    private final Predicate<Key<?>> boundInRoot;

    InjectionPlanner(RoleRegistry registry, Predicate<Key<?>> boundInRoot) {
        this.registry = registry;
        this.boundInRoot = boundInRoot;
    }

    /**
     * @param singletons classes bound as singletons of the scope (role implementations)
     * @param others     classes bound unscoped (the application object being created)
     * @param requester  plugin id for error messages, {@code null} for the application
     */
    Module plan(Collection<Class<?>> singletons, Collection<Class<?>> others, String requester) {
        Set<Class<?>> concrete = new LinkedHashSet<>();
        Map<Key<?>, Class<?>> roleSets = new LinkedHashMap<>();
        Map<Key<?>, Class<?>> roleSingles = new LinkedHashMap<>();

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
                classify(dependency.getKey(), work, roleSets, roleSingles);
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
            }
        };
    }

    private void classify(Key<?> key, Deque<Class<?>> work, Map<Key<?>, Class<?>> roleSets,
                          Map<Key<?>, Class<?>> roleSingles) {
        if (boundInRoot.test(key) || key.getAnnotationType() != null) {
            // Host services, or qualified keys we cannot guess: Guice resolves or reports them.
            return;
        }
        TypeLiteral<?> literal = key.getTypeLiteral();
        Class<?> raw = literal.getRawType();
        Type type = literal.getType();

        if (isProvider(raw) && type instanceof ParameterizedType p) {
            classify(Key.get(p.getActualTypeArguments()[0]), work, roleSets, roleSingles);
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
