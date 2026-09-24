package dev.crystal.plugins.api;

import java.util.List;

/**
 * What a plugin brings, known before installing it: what a catalog can tell from the plugin's
 * {@code META-INF/plugin-metadata.json} (written by the build) without the plugin being downloaded or loaded.
 *
 * @param implementsRoles role interfaces its extensions implement (class names), sorted, without repetitions
 * @param definesRoles    role interfaces it declares for sub-plugins (class names)
 * @param dependencies    ids of the plugins it requires
 * @param name            its name for people ({@code Plugin-Name} in the manifest), or null: show the id
 */
public record PluginDescription(List<String> implementsRoles, List<String> definesRoles, List<String> dependencies,
                                String name) {

    public PluginDescription(List<String> implementsRoles, List<String> definesRoles, List<String> dependencies) {
        this(implementsRoles, definesRoles, dependencies, null);
    }

    public PluginDescription {
        implementsRoles = List.copyOf(implementsRoles);
        definesRoles = List.copyOf(definesRoles);
        dependencies = List.copyOf(dependencies);
    }
}
