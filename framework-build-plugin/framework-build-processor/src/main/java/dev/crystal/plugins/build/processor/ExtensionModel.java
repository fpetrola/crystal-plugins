package dev.crystal.plugins.build.processor;

import java.util.List;

/**
 * One concrete class that implements at least one role.
 *
 * @param className binary name as used by {@code Class.forName} (nested classes use {@code $})
 * @param roles     role interfaces it implements (sorted)
 * @param replaces  values of {@code @Replaces} (sorted)
 * @param needs     values of {@code @Needs} (sorted)
 * @param lifecycle whether it implements {@code HasLifecycle}
 * @param serviceLoadable whether {@link java.util.ServiceLoader} can instantiate it (public no-arg constructor)
 * @param answers   role → keys of {@code @Answers} (both sorted); empty if it has none
 */
record ExtensionModel(String className, List<String> roles, List<String> replaces, List<String> needs,
                      boolean lifecycle, boolean serviceLoadable, java.util.Map<String, List<String>> answers) {
}
