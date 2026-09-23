package dev.crystal.plugins.runtime;

import java.util.List;
import java.util.Optional;

/**
 * One role and who implements it right now: the tree of plugins and sub-plugins, from the roles' side.
 *
 * @param role            the role interface
 * @param definedBy       the plugin that defines it; empty when the application does
 * @param implementations every active implementation, in the resolver's order of preference first (the visible
 *                        ones), then the hidden ones
 */
public record RoleInfo(String role, Optional<String> definedBy, List<Implementation> implementations) {

    public RoleInfo {
        implementations = List.copyOf(implementations);
    }

    /**
     * @param pluginId  the plugin it comes from
     * @param className its class
     * @param visible   false when the conflict resolver hides it (by default: replaced with {@code @Replaces})
     */
    public record Implementation(String pluginId, String className, boolean visible) {
    }
}
