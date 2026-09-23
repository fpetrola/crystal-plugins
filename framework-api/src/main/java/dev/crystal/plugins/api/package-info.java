/**
 * Public contract of the Crystal plugin framework.
 *
 * <p>This is the only framework package plugin authors and host applications see:
 * <ul>
 *   <li>{@link dev.crystal.plugins.api.RoleInterface} marks an interface as an extension point.</li>
 *   <li>{@link dev.crystal.plugins.api.Replaces} and {@link dev.crystal.plugins.api.Needs} record the few
 *       decisions the build cannot infer.</li>
 *   <li>{@link dev.crystal.plugins.api.HasLifecycle} is the opt-in lifecycle.</li>
 *   <li>{@link dev.crystal.plugins.api.PluginSource} is how the host supplies jars.</li>
 * </ul>
 * Dependencies between objects are expressed with standard {@code jakarta.inject.Inject}.
 * PF4J and Guice never appear in this API.
 */
package dev.crystal.plugins.api;
