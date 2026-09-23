package dev.crystal.plugins.api;

import java.util.List;

/**
 * Decides which implementations of a role are visible, and in which order of preference, when several are
 * active. The host application may supply its own; {@link #standard()} is the default.
 *
 * <p>The runtime calls it with every active implementation of a role and uses the answer everywhere:
 * {@code Set<Role>} views show exactly the returned implementations, in that order, and a single
 * {@code @Inject Role} gets the first. It is called again whenever the active implementations change, so
 * the decision is reversible: an implementation hidden now comes back when what hid it goes away. Results
 * are cached between changes, so a resolver may do real work.
 *
 * <p>A resolver may only return (a subset of) the implementations it was given. Since roles are defined by
 * the application, so is any domain rule ("one exporter per format", "never replace the built-in scaler"):
 * compose with the standard resolver to keep its behaviour for the rest,
 * {@code (role, all) -> myFilter(ConflictResolver.standard().resolve(role, all))}.
 */
public interface ConflictResolver {

    /**
     * @param role            the role being resolved
     * @param implementations every active implementation of it (at least one)
     * @return the visible implementations, most preferred first
     */
    List<RoleImplementation<?>> resolve(Class<?> role, List<RoleImplementation<?>> implementations);

    /**
     * The default resolver.
     * <ol>
     *   <li><b>{@code @Replaces} wins.</b> An implementation replaced by another active implementation of the
     *       same role is hidden, and stays hidden only while the replacing one is active. Chains work
     *       (C replaces B, B replaces A: only C is visible); replacements that form a cycle are ignored.</li>
     *   <li><b>Then the highest version.</b> The visible ones are ordered by plugin version (SemVer
     *       precedence, highest first), then by plugin id and class name, so the order is deterministic.</li>
     * </ol>
     */
    static ConflictResolver standard() {
        return StandardConflictResolver.INSTANCE;
    }
}
