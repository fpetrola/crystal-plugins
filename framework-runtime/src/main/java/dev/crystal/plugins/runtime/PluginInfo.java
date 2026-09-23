package dev.crystal.plugins.runtime;

import java.util.Optional;

/**
 * Read-only view of a loaded plugin, in framework terms (no PF4J types leak out).
 *
 * @param id      plugin id
 * @param version plugin version
 * @param status  current status
 * @param failure why the plugin failed, when {@code status == FAILED}
 */
public record PluginInfo(String id, String version, Status status, Optional<Throwable> failure) {

    public enum Status {
        /** Loaded and dependencies resolved, not started. */
        RESOLVED,
        /** Active: its role implementations are visible. */
        STARTED,
        STOPPED,
        FAILED,
        DISABLED,
        /** Removed by {@link PluginService#uninstall}: stopped, unloaded and no longer installed. */
        UNLOADED
    }
}
