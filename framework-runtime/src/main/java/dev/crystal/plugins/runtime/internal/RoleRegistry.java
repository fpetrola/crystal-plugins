package dev.crystal.plugins.runtime.internal;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.ProvisionException;

/**
 * The set of role implementations of every active plugin.
 *
 * <p>Consumers never receive a copy: {@link #view(Class)} returns a live, read-only {@link Set} that always
 * reflects the plugins active <em>now</em>. This is what makes {@code @Inject Set<SomeRole>} safe in objects
 * that outlive a plugin start/stop.
 *
 * <p>Copy-on-write: publication (rare) copies the list, iteration (frequent) is lock-free and never sees a
 * half-published plugin.
 */
public final class RoleRegistry {

    private volatile List<Contribution> contributions = List.of();
    private final Map<Class<?>, Set<?>> views = new ConcurrentHashMap<>();

    synchronized void publish(List<Contribution> added) {
        List<Contribution> next = new ArrayList<>(contributions);
        next.addAll(added);
        contributions = List.copyOf(next);
    }

    /**
     * Removes every contribution of {@code pluginId}. Cached views of roles <em>defined</em> by that plugin
     * (loaded by {@code pluginLoader}) are dropped too, so the registry never pins a plugin classloader.
     */
    synchronized void withdraw(String pluginId, ClassLoader pluginLoader) {
        contributions = contributions.stream().filter(c -> !c.pluginId().equals(pluginId)).toList();
        views.keySet().removeIf(role -> role.getClassLoader() == pluginLoader);
    }

    @SuppressWarnings("unchecked")
    public <T> Set<T> view(Class<T> role) {
        return (Set<T>) views.computeIfAbsent(role, r -> new LiveView<>(this, r));
    }

    /** The only implementation of {@code role}; used for {@code @Inject SomeRole}. */
    <T> T single(Class<T> role, String requester) {
        List<Contribution> matches = contributions.stream().filter(c -> c.roles().contains(role)).toList();
        if (matches.size() == 1) {
            return role.cast(matches.get(0).instance());
        }
        String who = requester == null ? "the application" : "plugin '" + requester + "'";
        if (matches.isEmpty()) {
            throw new ProvisionException(who + " injects " + role.getName()
                    + " but no active plugin provides it (inject Set<" + role.getSimpleName()
                    + "> to accept zero or many, or declare a dependency on the providing plugin)");
        }
        throw new ProvisionException(who + " injects a single " + role.getName() + " but "
                + matches.size() + " plugins provide it: "
                + matches.stream().map(Contribution::pluginId).toList()
                + " (inject Set<" + role.getSimpleName() + "> or use @Replaces)");
    }

    private int count(Class<?> role) {
        int n = 0;
        for (Contribution c : contributions) {
            if (c.roles().contains(role)) {
                n++;
            }
        }
        return n;
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
            List<Contribution> snapshot = registry.contributions;
            return new Iterator<>() {
                private int index = advance(0);

                private int advance(int from) {
                    int i = from;
                    while (i < snapshot.size() && !snapshot.get(i).roles().contains(role)) {
                        i++;
                    }
                    return i;
                }

                @Override
                public boolean hasNext() {
                    return index < snapshot.size();
                }

                @Override
                public T next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    T value = role.cast(snapshot.get(index).instance());
                    index = advance(index + 1);
                    return value;
                }
            };
        }

        @Override
        public int size() {
            return registry.count(role);
        }

        @Override
        public String toString() {
            return "Set<" + role.getSimpleName() + ">" + super.toString();
        }
    }
}
