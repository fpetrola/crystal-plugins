package dev.crystal.plugins.api;

import java.util.List;
import java.util.Objects;

/**
 * One active implementation of a role, as a {@link ConflictResolver} sees it.
 *
 * @param instance      the implementation object
 * @param pluginId      the plugin that contributes it
 * @param pluginVersion that plugin's version
 * @param replaces      the values of its {@link Replaces} annotation (empty if it has none)
 */
public record RoleImplementation<T>(T instance, String pluginId, String pluginVersion, List<String> replaces) {

    public RoleImplementation {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        replaces = List.copyOf(replaces);
    }

    /** {@code plugin-id:fully.qualified.ClassName}: the form {@link Replaces} uses to target a single class. */
    public String id() {
        return pluginId + ":" + instance.getClass().getName();
    }

    /** Whether this implementation's {@code @Replaces} targets {@code other}, by plugin id or by {@link #id()}. */
    public boolean replaces(RoleImplementation<?> other) {
        return other != this && (replaces.contains(other.pluginId()) || replaces.contains(other.id()));
    }
}
