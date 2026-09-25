package dev.crystal.plugins.api;

import java.util.List;

/**
 * What a plugin brings, known before installing it: what a catalog can tell from the plugin's
 * {@code META-INF/plugin-metadata.json} (written by the build) without the plugin being downloaded or loaded.
 *
 * @param implementsRoles role interfaces its extensions implement (class names), sorted, without repetitions
 * @param definesRoles    role interfaces it declares for sub-plugins (class names)
 * @param dependencies    ids of the plugins it requires
 * @param answers         role → the keys its extensions answer for it ({@link Answers})
 * @param name            its name for people ({@code Plugin-Name} in the manifest), or null: show the id
 * @param offers          the actions its extensions offer ({@link Offers})
 */
public record PluginDescription(List<String> implementsRoles, List<String> definesRoles, List<String> dependencies,
                                String name, java.util.Map<String, List<String>> answers, List<Offer> offers) {

    public PluginDescription {
        implementsRoles = List.copyOf(implementsRoles);
        definesRoles = List.copyOf(definesRoles);
        dependencies = List.copyOf(dependencies);
        answers = java.util.Map.copyOf(answers);
        offers = List.copyOf(offers);
    }

    public PluginDescription(List<String> implementsRoles, List<String> definesRoles, List<String> dependencies,
                             String name, java.util.Map<String, List<String>> answers) {
        this(implementsRoles, definesRoles, dependencies, name, answers, List.of());
    }

    public PluginDescription(List<String> implementsRoles, List<String> definesRoles, List<String> dependencies,
                             String name) {
        this(implementsRoles, definesRoles, dependencies, name, java.util.Map.of());
    }

    public PluginDescription(List<String> implementsRoles, List<String> definesRoles, List<String> dependencies) {
        this(implementsRoles, definesRoles, dependencies, null);
    }

    /** Whether it answers {@code key} (ignoring case) for {@code role} (a class name). */
    public boolean answers(String role, String key) {
        return answers.getOrDefault(role, List.of()).stream().anyMatch(k -> k.equalsIgnoreCase(key));
    }
}
