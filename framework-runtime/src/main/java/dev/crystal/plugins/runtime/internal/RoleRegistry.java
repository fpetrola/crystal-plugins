package dev.crystal.plugins.runtime.internal;

import java.lang.ref.WeakReference;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

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
 *
 * <p>It also knows who holds a <em>fixed</em> reference to an implementation: what received it through a single
 * {@code @Inject Role} while being built (a plugin's singletons, an object made with {@code create()}). Live
 * views and providers are not fixed references. A plugin is only unloaded when nothing outside the plugins
 * being unloaded holds one of its implementations (see {@link #holdersOutside}).
 */
public final class RoleRegistry {

    /** The plugins whose implementations were handed out while the current thread builds something. */
    private static final ThreadLocal<List<String>> HANDED_OUT = new ThreadLocal<>();

    /** Something built by {@link #build}, with the plugins whose implementations it received fixed. */
    record Built<T>(T value, List<String> owners) {
    }

    /** An application object holding a fixed reference; weak, so it does not keep itself alive. */
    private record ApplicationHolder(WeakReference<Object> object, String type) {
    }

    /** Every contribution, and each role resolved from them (filled lazily). */
    private record Snapshot(List<Contribution> contributions, Map<Class<?>, List<Contribution>> resolved) {

        Snapshot(List<Contribution> contributions) {
            this(List.copyOf(contributions), new ConcurrentHashMap<>());
        }
    }

    private final ConflictResolver resolver;
    private volatile Snapshot snapshot = new Snapshot(List.of());
    private final Map<Class<?>, Set<?>> views = new ConcurrentHashMap<>();
    /** Owner plugin → plugins holding fixed references to its implementations. */
    private final Map<String, Set<String>> pluginHolders = new ConcurrentHashMap<>();
    /** Owner plugin → application objects holding fixed references to its implementations. */
    private final Map<String, List<ApplicationHolder>> applicationHolders = new ConcurrentHashMap<>();

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
        pluginHolders.remove(pluginId);
        pluginHolders.values().forEach(holders -> holders.remove(pluginId));
        applicationHolders.remove(pluginId);
    }

    /** Runs {@code build}, recording whose implementations it receives as fixed references. */
    <T> Built<T> build(Supplier<T> build) {
        List<String> previous = HANDED_OUT.get();
        List<String> owners = new ArrayList<>();
        HANDED_OUT.set(owners);
        try {
            T value = build.get();
            return new Built<>(value, List.copyOf(owners));
        } finally {
            if (previous == null) {
                HANDED_OUT.remove();
            } else {
                HANDED_OUT.set(previous);
            }
        }
    }

    void heldByPlugin(String holder, Collection<String> owners) {
        for (String owner : owners) {
            if (!owner.equals(holder)) {
                pluginHolders.computeIfAbsent(owner, o -> ConcurrentHashMap.newKeySet()).add(holder);
            }
        }
    }

    void heldByApplication(Object holder, Collection<String> owners) {
        for (String owner : owners) {
            applicationHolders.computeIfAbsent(owner, o -> new CopyOnWriteArrayList<>())
                    .add(new ApplicationHolder(new WeakReference<>(holder), holder.getClass().getName()));
        }
    }

    /**
     * Who holds fixed references to implementations of the plugins {@code unloading} from outside that set: plugins
     * not in it ({@code plugin 'id'}) and application objects still alive (their class name). Empty when the set is
     * clean, that is, can be unloaded now. Distinct, in discovery order.
     */
    public List<String> holdersOutside(Set<String> unloading) {
        Set<String> holders = new java.util.LinkedHashSet<>();
        for (String owner : unloading) {
            for (String holder : pluginHolders.getOrDefault(owner, Set.of())) {
                if (!unloading.contains(holder)) {
                    holders.add("plugin '" + holder + "'");
                }
            }
            List<ApplicationHolder> alive = applicationHolders.get(owner);
            if (alive != null) {
                alive.removeIf(h -> h.object().get() == null);
                alive.forEach(h -> holders.add(h.type()));
            }
        }
        return List.copyOf(holders);
    }

    @SuppressWarnings("unchecked")
    public <T> Set<T> view(Class<T> role) {
        return (Set<T>) views.computeIfAbsent(role, r -> new LiveView<>(this, r));
    }

    /** The preferred implementation of {@code role}; used for {@code @Inject SomeRole}. */
    <T> T single(Class<T> role, String requester) {
        List<Contribution> resolved = resolved(role);
        if (resolved.isEmpty()) {
            String who = requester == null ? "the application" : "plugin '" + requester + "'";
            throw new NoSuchElementException(who + " injects " + role.getName() + " but no active plugin provides it "
                    + "(inject Set<" + role.getSimpleName() + "> to accept zero or many, or declare a dependency on "
                    + "the providing plugin)");
        }
        Contribution chosen = resolved.get(0);
        List<String> handedOut = HANDED_OUT.get();
        if (handedOut != null) {
            handedOut.add(chosen.pluginId());
        }
        return role.cast(chosen.instance());
    }

    /**
     * The visible implementations of {@code role} now, as a fixed list. Inside {@link #build} they are recorded as
     * handed out: whoever keeps the list holds them all.
     */
    <T> List<T> fixed(Class<T> role) {
        List<Contribution> resolved = resolved(role);
        List<String> handedOut = HANDED_OUT.get();
        List<T> result = new ArrayList<>(resolved.size());
        for (Contribution c : resolved) {
            if (handedOut != null) {
                handedOut.add(c.pluginId());
            }
            result.add(role.cast(c.instance()));
        }
        return List.copyOf(result);
    }

    /** The visible implementations of {@code role}, most preferred first, in the current snapshot. */
    List<Contribution> resolved(Class<?> role) {
        Snapshot current = snapshot;
        List<Contribution> cached = current.resolved().get(role);
        if (cached == null) {
            // Not computeIfAbsent: the resolver is application code and may itself read the registry.
            cached = resolve(role, current.contributions());
            List<Contribution> raced = current.resolved().putIfAbsent(role, cached);
            if (raced != null) {
                cached = raced;
            }
        }
        return cached;
    }

    private List<Contribution> resolve(Class<?> role, List<Contribution> contributions) {
        Map<RoleImplementation<?>, Contribution> candidates = new IdentityHashMap<>();
        List<RoleImplementation<?>> offered = new ArrayList<>();
        for (Contribution c : contributions) {
            if (c.roles().contains(role)) {
                RoleImplementation<?> implementation =
                        new RoleImplementation<>(c.instance(), c.pluginId(), c.pluginVersion(), c.replaces());
                candidates.put(implementation, c);
                offered.add(implementation);
            }
        }
        if (offered.isEmpty()) {
            return List.of();
        }
        List<RoleImplementation<?>> chosen = resolver.resolve(role, Collections.unmodifiableList(offered));
        Set<RoleImplementation<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Contribution> result = new ArrayList<>(chosen.size());
        for (RoleImplementation<?> implementation : chosen) {
            if (!candidates.containsKey(implementation) || !seen.add(implementation)) {
                throw new IllegalStateException(resolver + " must return a subset of the implementations it is "
                        + "given, without repetitions; it returned " + implementation + " for " + role.getName());
            }
            result.add(candidates.get(implementation));
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
            Iterator<Contribution> resolved = registry.resolved(role).iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return resolved.hasNext();
                }

                @Override
                public T next() {
                    return role.cast(resolved.next().instance());
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
