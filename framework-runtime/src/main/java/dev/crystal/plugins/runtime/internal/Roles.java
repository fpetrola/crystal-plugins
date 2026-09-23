package dev.crystal.plugins.runtime.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import dev.crystal.plugins.api.RoleInterface;

/** Reflection helpers around {@link RoleInterface}. */
public final class Roles {

    private Roles() {
    }

    public static boolean isRole(Class<?> type) {
        return type.isInterface() && type.isAnnotationPresent(RoleInterface.class);
    }

    /** Every role interface {@code type} implements, directly or through supertypes, in discovery order. */
    public static Set<Class<?>> of(Class<?> type) {
        Set<Class<?>> roles = new LinkedHashSet<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> work = new ArrayDeque<>();
        work.add(type);
        while (!work.isEmpty()) {
            Class<?> current = work.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (isRole(current)) {
                roles.add(current);
            }
            if (current.getSuperclass() != null) {
                work.add(current.getSuperclass());
            }
            for (Class<?> i : current.getInterfaces()) {
                work.add(i);
            }
        }
        return roles;
    }

    /**
     * Detects the classic packaging mistake: a plugin jar that bundles framework-api (or the app API), so its
     * classes see a different {@code RoleInterface} class than the host. Returns a hint, or {@code null}.
     */
    static String foreignRoleAnnotationHint(Class<?> type) {
        for (Class<?> i : type.getInterfaces()) {
            for (var annotation : i.getAnnotations()) {
                Class<?> annotationType = annotation.annotationType();
                if (annotationType.getName().equals(RoleInterface.class.getName())
                        && annotationType != RoleInterface.class) {
                    return type.getName() + " implements " + i.getName() + ", annotated with a RoleInterface loaded by "
                            + annotationType.getClassLoader() + "; the plugin jar probably bundles framework-api "
                            + "or the application API (they must be 'provided')";
                }
            }
        }
        return null;
    }
}
