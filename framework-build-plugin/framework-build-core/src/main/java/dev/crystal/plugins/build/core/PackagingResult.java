package dev.crystal.plugins.build.core;

import java.util.List;
import java.util.Set;

/**
 * Outcome of {@link PluginPackager#finish}.
 *
 * @param plugin       false when the module implements no role: the jar was left untouched
 * @param version      the SemVer written as {@code Plugin-Version}
 * @param roles        roles implemented by the plugin
 * @param dependencies plugins it depends on, sorted by id
 * @param warnings     problems that do not stop the build but will probably bite at runtime
 */
public record PackagingResult(boolean plugin, String pluginId, String version, Set<String> roles,
                              List<PluginDependency> dependencies, List<String> warnings) {

    public PackagingResult {
        roles = Set.copyOf(roles);
        dependencies = List.copyOf(dependencies);
        warnings = List.copyOf(warnings);
    }

    static PackagingResult notAPlugin() {
        return new PackagingResult(false, null, null, Set.of(), List.of(), List.of());
    }
}
