package dev.crystal.plugins.build.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginBundlerTest {

    @TempDir
    Path dir;

    private Path artifact(Path repo, String group, String artifactId, String version, Map<String, String> manifest)
            throws IOException {
        Path classes = Files.createDirectories(dir.resolve("c-" + artifactId + version));
        Path folder = Files.createDirectories(repo.resolve(group.replace('.', '/')).resolve(artifactId).resolve(version));
        return Fixtures.jar(classes, folder.resolve(artifactId + "-" + version + ".jar"), manifest);
    }

    @Test
    void bundlesThePluginsOfTheGroupAtTheVersion() throws IOException {
        Path repo = dir.resolve("repo");
        artifact(repo, "com.acme.plugins", "csv", "1.0.0", Map.of("Plugin-Id", "csv", "Plugin-Version", "1.0.0"));
        artifact(repo, "com.acme.plugins", "csv", "2.0.0", Map.of("Plugin-Id", "csv", "Plugin-Version", "2.0.0"));
        artifact(repo, "com.acme", "util", "1.0.0", Map.of());
        artifact(repo, "com.acme", "app", "1.0.0", Map.of("Plugin-Id", "app", "Plugin-Version", "1.0.0"));
        artifact(repo, "org.other", "md", "1.0.0", Map.of("Plugin-Id", "md", "Plugin-Version", "1.0.0"));
        Path classes = Files.createDirectories(dir.resolve("classes"));

        List<PluginBundler.Bundled> bundled = PluginBundler.bundle(repo, "com.acme", "1.0.0", "com.acme:app", classes);

        assertEquals(List.of("csv"), bundled.stream().map(PluginBundler.Bundled::id).toList(),
                "sub-groups included; other versions, libraries, the application and other groups not");
        assertEquals("com.acme.plugins:csv:1.0.0", bundled.get(0).coordinates());
        assertTrue(Files.isRegularFile(classes.resolve("META-INF/crystal/bundled/csv-1.0.0.jar")));
        List<String> index = Files.readAllLines(classes.resolve(PluginBundler.INDEX));
        assertEquals("csv\t1.0.0\t" + bundled.get(0).sha256() + "\tcsv-1.0.0.jar", index.get(1));
    }
}
