package dev.crystal.plugins.build.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One element of the plugin's compile classpath, as the build tool resolved it.
 *
 * @param path       jar file or class directory
 * @param groupId    coordinates, for messages and metadata
 * @param artifactId coordinates, for messages and metadata
 * @param version    resolved version (for a SNAPSHOT, its base version)
 * @param provided   supplied by the host at runtime rather than bundled: Maven {@code provided}/{@code system},
 *                   Gradle {@code compileOnly}. A provided entry that is not a plugin is shared API.
 */
public record ClasspathEntry(Path path, String groupId, String artifactId, String version, boolean provided) {

    public ClasspathEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
    }

    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
