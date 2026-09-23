package dev.crystal.plugins.runtime.internal;

import java.util.Set;

/** One live role implementation, owned by a plugin. */
record Contribution(String pluginId, Object instance, Set<Class<?>> roles) {
}
