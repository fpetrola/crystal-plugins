package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.runtime.fixtures.ReportExporter;

/** Default plugins carried inside the application, installed on the first start without network. */
class BundledDefaultsTest {

    @TempDir
    Path dir;

    @TempDir
    Path cache;

    /** What bundle-plugins leaves in the application's classes (written by hand: the manual path). */
    private ClassLoader application(String... ids) throws IOException {
        Path classes = dir.resolve("app-classes");
        Path bundled = Files.createDirectories(classes.resolve("META-INF/crystal/bundled"));
        StringBuilder index = new StringBuilder("# id, version, sha256, file\n");
        for (String id : ids) {
            Path jar = PluginJars.plugin(id, "1.0.0").withProcessor().source("acme." + id + ".Exporter", """
                    package acme.%s;
                    import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                    import java.util.List;
                    public class Exporter implements ReportExporter {
                        public String format() { return "%s"; }
                        public String export(List<String> rows) { return ""; }
                    }
                    """.formatted(id, id)).buildInto(bundled);
            index.append(id).append("\t1.0.0\t").append(PluginSources.sha256(jar)).append('\t')
                    .append(jar.getFileName()).append('\n');
        }
        Files.writeString(classes.resolve("META-INF/crystal/bundled.idx"), index);
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private PluginService service(ClassLoader app) {
        return PluginService.builder().defaults(PluginSources.bundled(app)).cacheDirectory(cache).build();
    }

    private static Set<String> formats(PluginService plugins) {
        return plugins.roles(ReportExporter.class).stream().map(ReportExporter::format).collect(Collectors.toSet());
    }

    @Test
    void theFirstStartInstallsTheBundledDefaultsAndLaterStartsRespectTheUser() throws IOException {
        ClassLoader app = application("csv", "md");

        try (PluginService plugins = service(app)) {
            plugins.start();
            assertEquals(Set.of("csv", "md"), formats(plugins), "extracted into the cache, no source, no network");
            plugins.uninstall("md");
        }
        try (PluginService plugins = service(app)) {
            plugins.start();
            assertEquals(Set.of("csv"), formats(plugins), "an uninstalled default does not come back");
            plugins.install("md");
            assertEquals(Set.of("csv", "md"), formats(plugins), "and can be installed again from inside the app");
        }
        assertTrue(Files.isDirectory(cache.resolve("objects")));
    }

    @Test
    void reinstallingABundledPluginTakesItFromTheAppUnlessTheCatalogHasANewerOne() throws IOException {
        ClassLoader app = application("csv", "md");
        // The catalog offers csv in the same version (built elsewhere: other bytes) and md in a newer one.
        Path catalog = Files.createDirectories(dir.resolve("catalog"));
        for (String[] plugin : new String[][] {{"csv", "1.0.0", "csv-remote"}, {"md", "2.0.0", "md-2"}}) {
            PluginJars.plugin(plugin[0], plugin[1]).withProcessor().source("acme." + plugin[0] + ".Exporter", """
                    package acme.%s;
                    import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                    import java.util.List;
                    public class Exporter implements ReportExporter {
                        public String format() { return "%s"; }
                        public String export(List<String> rows) { return ""; }
                    }
                    """.formatted(plugin[0], plugin[2])).buildInto(catalog);
        }
        List<String> downloads = new java.util.ArrayList<>();
        dev.crystal.plugins.api.PluginSource directory = PluginSources.directory(catalog);
        dev.crystal.plugins.api.PluginSource counting = new dev.crystal.plugins.api.PluginSource() {
            @Override
            public List<dev.crystal.plugins.api.PluginArtifact> artifacts() throws IOException {
                return directory.artifacts();
            }

            @Override
            public java.io.InputStream open(dev.crystal.plugins.api.PluginArtifact artifact) throws IOException {
                downloads.add(artifact.id());
                return directory.open(artifact);
            }
        };
        try (PluginService plugins = PluginService.builder().source(counting).defaults(PluginSources.bundled(app))
                .cacheDirectory(cache).build()) {
            plugins.start();
            plugins.uninstall("csv");
            plugins.uninstall("md");
            plugins.install("csv");
            plugins.install("md");
            assertEquals(Set.of("csv", "md-2"), formats(plugins), "the same csv as before; md updated");
            assertEquals(List.of("md"), downloads, "only the newer version was downloaded");
            assertEquals(List.of(), plugins.checkForUpdates().changes(), "the bundled csv is not an update away");
        }
    }
}
