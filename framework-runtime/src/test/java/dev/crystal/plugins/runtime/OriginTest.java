package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;

/** A source that merges places says which one each artifact comes from. */
class OriginTest {

    @TempDir
    Path repo;

    @Test
    void theSourceSaysWhereEachArtifactComesFrom() {
        PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", """
                package acme.csv;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "csv"; }
                    public String export(List<String> rows) { return ""; }
                }
                """).buildInto(repo);
        PluginSource folder = PluginSources.directory(repo);
        PluginSource named = new PluginSource() {
            public List<PluginArtifact> artifacts() throws IOException {
                return folder.artifacts();
            }

            public InputStream open(PluginArtifact artifact) throws IOException {
                return folder.open(artifact);
            }

            @Override
            public String origin(PluginArtifact artifact) {
                return "plugins folder";
            }
        };
        try (PluginService plugins = PluginService.builder().source(named)
                .defaults(PluginSources.bundled(getClass().getClassLoader())).build()) {
            PluginArtifact csv = plugins.available().get(0);
            assertEquals("plugins folder", plugins.origin(csv), "through the layered catalog too");
        }
    }
}
