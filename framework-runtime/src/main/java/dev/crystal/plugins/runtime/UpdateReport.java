package dev.crystal.plugins.runtime;

import java.util.List;

import dev.crystal.plugins.api.PluginArtifact;

/**
 * Outcome of {@link PluginService#checkForUpdates()} or {@link PluginService#installAll()}.
 *
 * <p>Changes are staged in the cache and take effect the next time a {@link PluginService} starts on it.
 * Plugins that are already running keep running on the jars they were loaded from.
 *
 * @param changes    what changed in the installed set, sorted by plugin id
 * @param downloads  number of jars actually downloaded (the rest were already cached)
 * @param available  offered by the source but not installed ({@link PluginService#install} adds one); always
 *                   empty after {@code installAll()}
 * @param notOffered installed plugins the source no longer offers; they stay installed
 */
public record UpdateReport(List<Change> changes, int downloads, List<PluginArtifact> available,
                           List<String> notOffered) {

    public UpdateReport {
        changes = List.copyOf(changes);
        available = List.copyOf(available);
        notOffered = List.copyOf(notOffered);
    }

    public enum Kind {
        ADDED,
        /** Different jar for the same plugin id: new version, or rebuilt bytes of the same version. */
        UPDATED,
        REMOVED
    }

    /**
     * @param fromVersion installed version before; {@code null} for {@link Kind#ADDED}
     * @param toVersion   installed version after; {@code null} for {@link Kind#REMOVED}
     */
    public record Change(Kind kind, String id, String fromVersion, String toVersion) {
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }
}
