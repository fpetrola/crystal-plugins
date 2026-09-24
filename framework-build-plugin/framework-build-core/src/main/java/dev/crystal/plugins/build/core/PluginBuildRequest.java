package dev.crystal.plugins.build.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Everything the build tool knows about the plugin being packaged.
 *
 * @param classesDirectory output of the compilation of the plugin's sources
 * @param jar              the jar already built from it; finished in place
 * @param pluginId         {@code Plugin-Id} (conventionally the artifactId)
 * @param version          project version as the build tool has it; mapped to SemVer
 * @param groupId          project coordinates
 * @param artifactId       project coordinates
 * @param description      free text, may be null
 * @param classpath        compile classpath, in resolution order
 * @param name             name for people ({@code Plugin-Name}), may be null: tools then show the id
 */
public record PluginBuildRequest(Path classesDirectory, Path jar, String pluginId, String version, String groupId,
                                 String artifactId, String description, List<ClasspathEntry> classpath,
                                 String name) {

    public PluginBuildRequest(Path classesDirectory, Path jar, String pluginId, String version, String groupId,
                              String artifactId, String description, List<ClasspathEntry> classpath) {
        this(classesDirectory, jar, pluginId, version, groupId, artifactId, description, classpath, null);
    }

    public PluginBuildRequest {
        Objects.requireNonNull(classesDirectory, "classesDirectory");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        classpath = List.copyOf(classpath);
    }
}
