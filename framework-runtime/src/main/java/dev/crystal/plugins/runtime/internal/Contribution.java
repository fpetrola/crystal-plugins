package dev.crystal.plugins.runtime.internal;

import java.util.List;
import java.util.Set;

/**
 * One live role implementation, owned by a plugin.
 *
 * @param replaces values of the implementation's {@code @Replaces}, read from its class
 */
record Contribution(String pluginId, String pluginVersion, Object instance, Set<Class<?>> roles,
                    List<String> replaces) {
}
