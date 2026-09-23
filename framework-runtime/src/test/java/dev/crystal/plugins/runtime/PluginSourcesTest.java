package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.PluginArtifact;

class PluginSourcesTest {

    @TempDir
    Path repo;

    @Test
    void directoryOffersOnlyJarsThatArePlugins() throws IOException {
        Path plugin = PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", """
                package acme.csv;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "csv"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """).buildInto(repo);
        // Typical stowaway: a build tool's jar with a manifest but no plugin identity.
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(repo.resolve("surefirebooter-123.jar"));
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.finish();
        }

        List<PluginArtifact> artifacts = PluginSources.directory(repo).artifacts();

        assertEquals(List.of(new PluginArtifact("csv", "1.0.0", PluginSources.sha256(plugin))), artifacts);
    }
}
