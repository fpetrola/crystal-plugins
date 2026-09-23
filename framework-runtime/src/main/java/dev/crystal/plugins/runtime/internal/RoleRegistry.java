package dev.crystal.plugins.runtime.internal;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.ProvisionException;

import dev.crystal.plugins.api.ConflictResolver;
import dev.crystal.plugins.api.RoleImplementation;

/**
 * The role implementations of every active plugin, as the {@link ConflictResolver} lets them be seen.
 *
 * <p>Consumers never receive a copy: {@link #view(Class)} returns a live, read-only {@link Set} that always
 * reflects the plugins active <em>now</em>. This is what makes {@code @Inject Set<SomeRole>} safe in objects
 * that outlive a plugin start/stop, and what makes replacements reversible: nothing is ever removed because
 * something replaced it, it is only hidden while the replacing implementation is active.
 *
 * <p>Copy-on-write snapshots: publication (rare) builds a new snapshot; reading (frequent) is lock-free, never
 * sees a half-published plugin, and resolves each role at most once per snapshot.
 */
public final class RoleRegistry {

    /** Every contribution, and each role resolved from them (filled lazily). */
    private record Snapshot(List<Contribution> contributions, Map<Class<?>, List<Object>> resolved) {

        Snapshot(List<Contribution> contributions) {
            this(List.copyOf(contributions), new ConcurrentHashMap<>());
        }
    }

    private final ConflictResolver resolver;
    private volatile Snapshot snapshot = new Snapshot(List.of());
    private final Map<Class<?>, Set<?>> views = new ConcurrentHashMap<>();

    public RoleRegistry(ConflictResolver resolver) {
        this.resolver = resolver;
    }

    synchronized void publish(List<Contribution> added) {
        List<Contribution> next = new ArrayList<>(snapshot.contributions());
        next.addAll(added);
        snapshot = new Snapshot(next);
    }

    /**
     * Removes every contribution of {@code pluginId}. Cached views of roles <em>defined</em> by that plugin
     * (loaded by {@code pluginLoader}) are dropped too, so the registry never pins a plugin classloader.
     */
    synchronized void withdraw(String pluginId, ClassLoader pluginLoader) {
        snapshot = new Snapshot(snapshot.contributions().stream().filter(c -> !c.pluginId().equals(pluginId)).toList());
        views.keySet().removeIf(role -> role.getClassLoader() == pluginLoader);
    }

    @SuppressWarnings("unchecked")
    public <T> Set<T> view(Class<T> role) {
        return (Set<T>) views.computeIfAbsent(role, r -> new LiveView<>(this, r));
    }

    /** The preferred implementation of {@code role}; used for {@code @Inject SomeRole}. */
    <T> T single(Class<T> role, String requester) {
        List<Object> resolved = resolved(role);
        if (resolved.isEmpty()) {
            String who = requester == null ? "the application" : "plugin '" + requester + "'";
            throw new ProvisionException(who + " injects " + role.getName() + " but no active plugin provides it "
                    + "(inject Set<" + role.getSimpleName() + "> to accept zero or many, or declare a dependency on "
                    + "the providing plugin)");
        }
        return role.cast(resolved.get(0));
    }

    /** The visible implementations of {@code role}, most preferred first, in the current snapshot. */
    List<Object> resolved(Class<?> role) {
        Snapshot current = snapshot;
        List<Object> cached = current.resolved().get(role);
        if (cached == null) {
            // Not computeIfAbsent: the resolver is application code and may itself read the registry.
            cached = resolve(role, current.contributions());
            List<Object> raced = current.resolved().putIfAbsent(role, cached);
            if (raced != null) {
                cached = raced;
            }
        }
        return cached;
    }

    private List<Object> resolve(Class<?> role, List<Contribution> contributions) {
        List<RoleImplementation<?>> candidates = new ArrayList<>();
        for (Contribution c : contributions) {
            if (c.roles().contains(role)) {
                candidates.add(new RoleImplementation<>(c.instance(), c.pluginId(), c.pluginVersion(), c.replaces()));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RoleImplementation<?>> chosen = resolver.resolve(role, Collections.unmodifiableList(candidates));
        Set<RoleImplementation<?>> offered = Collections.newSetFromMap(new IdentityHashMap<>());
        offered.addAll(candidates);
        Set<RoleImplementation<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> result = new ArrayList<>(chosen.size());
        for (RoleImplementation<?> implementation : chosen) {
            if (!offered.contains(implementation) || !seen.add(implementation)) {
                throw new IllegalStateException(resolver + " must return a subset of the implementations it is "
                        + "given, without repetitions; it returned " + implementation + " for " + role.getName());
            }
            result.add(implementation.instance());
        }
        return List.copyOf(result);
    }

    private static final class LiveView<T> extends AbstractSet<T> {
        private final RoleRegistry registry;
        private final Class<T> role;

        LiveView(RoleRegistry registry, Class<T> role) {
            this.registry = registry;
            this.role = role;
        }

        @Override
        public Iterator<T> iterator() {
            Iterator<Object> resolved = registry.resolved(role).iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return resolved.hasNext();
                }

                @Override
                public T next() {
                    return role.cast(resolved.next());
                }
            };
        }

        @Override
        public int size() {
            return registry.resolved(role).size();
        }

        @Override
        public String toString() {
            return "Set<" + role.getSimpleName() + ">" + super.toString();
        }
    }
}
