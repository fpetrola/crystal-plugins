package dev.crystal.plugins.runtime;

import java.util.List;

/**
 * Outcome of {@link PluginService#checkForUpdates()}.
 *
 * <p>Changes are staged in the cache and take effect the next time a {@link PluginService} starts on it.
 * Plugins that are already running keep running on the jars they were loaded from.
 *
 * @param changes   differences between the previous installed set and the new one, sorted by plugin id
 * @param downloads number of jars actually downloaded (the rest were already cached)
 */
public record UpdateReport(List<Change> changes, int downloads) {

    public UpdateReport {
        changes = List.copyOf(changes);
    }

    public enum Kind {
        ADDED,
        /** Different jar for the same plugin id: new version, or rebuilt bytes of the same version. */
        UPDATED,
        REMOVED
    }

    /**
     * @param fromVersion installed version before the update; {@code null} for {@link Kind#ADDED}
     * @param toVersion   installed version after the update; {@code null} for {@link Kind#REMOVED}
     */
    public record Change(Kind kind, String id, String fromVersion, String toVersion) {
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }
}
