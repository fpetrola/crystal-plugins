package dev.crystal.plugins.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** See {@link ConflictResolver#standard()}. */
final class StandardConflictResolver implements ConflictResolver {

    static final StandardConflictResolver INSTANCE = new StandardConflictResolver();

    private static final Comparator<RoleImplementation<?>> PREFERENCE =
            Comparator.<RoleImplementation<?>, String>comparing(RoleImplementation::pluginVersion, SemanticVersions::compare)
                    .reversed()
                    .thenComparing(RoleImplementation::pluginId)
                    .thenComparing(i -> i.instance().getClass().getName());

    private StandardConflictResolver() {
    }

    @Override
    public List<RoleImplementation<?>> resolve(Class<?> role, List<RoleImplementation<?>> implementations) {
        List<RoleImplementation<?>> visible = new ArrayList<>();
        for (RoleImplementation<?> candidate : implementations) {
            if (!isReplaced(candidate, implementations)) {
                visible.add(candidate);
            }
        }
        visible.sort(PREFERENCE);
        return visible;
    }

    /** Replaced by another active implementation, through an edge that is not part of a cycle. */
    private static boolean isReplaced(RoleImplementation<?> candidate, List<RoleImplementation<?>> all) {
        for (RoleImplementation<?> other : all) {
            if (other.replaces(candidate) && !reaches(candidate, other, all)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code from} replaces {@code to}, directly or through a chain of replacements. */
    private static boolean reaches(RoleImplementation<?> from, RoleImplementation<?> to, List<RoleImplementation<?>> all) {
        Set<RoleImplementation<?>> seen = new HashSet<>();
        Deque<RoleImplementation<?>> work = new ArrayDeque<>();
        work.add(from);
        while (!work.isEmpty()) {
            RoleImplementation<?> current = work.pop();
            if (!seen.add(current)) {
                continue;
            }
            for (RoleImplementation<?> next : all) {
                if (current.replaces(next)) {
                    if (next == to) {
                        return true;
                    }
                    work.push(next);
                }
            }
        }
        return false;
    }
}
