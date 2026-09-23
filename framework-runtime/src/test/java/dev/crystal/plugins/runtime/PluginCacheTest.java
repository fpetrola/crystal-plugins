package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;
import dev.crystal.plugins.runtime.UpdateReport.Change;
import dev.crystal.plugins.runtime.UpdateReport.Kind;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;

/** Milestone 4: persistent local cache, explicit updates, integrity. */
class PluginCacheTest {

    @TempDir
    Path repo;

    @TempDir
    Path cache;

    @Test
    void startsFromTheCacheWithoutConsultingTheSource() {
        publish("csv", "1.0.0", "csv");
        publish("md", "1.0.0", "md");
        RecordingSource source = new RecordingSource(PluginSources.directory(repo));

        try (PluginService plugins = service(source)) {
            plugins.start();
            assertEquals(Set.of("csv", "md"), formats(plugins));
        }
        assertEquals(1, source.listings, "first start installs from the source");
        assertEquals(2, source.downloads);

        try (PluginService plugins = service(source)) {
            plugins.start();
            assertEquals(Set.of("csv", "md"), formats(plugins));
        }
        assertEquals(1, source.listings, "later starts do not even list the source");
        assertEquals(2, source.downloads);

        source.offline = true;
        try (PluginService plugins = service(source)) {
            plugins.start();
            assertEquals(Set.of("csv", "md"), formats(plugins), "works offline");
        }
        try (PluginService plugins = PluginService.builder().cacheDirectory(cache).build()) {
            plugins.start();
            assertEquals(Set.of("csv", "md"), formats(plugins), "works with no source at all");
        }
    }

    @Test
    void checkForUpdatesStagesTheNewSetForTheNextStart() throws IOException {
        publish("csv", "1.0.0", "csv-v1");
        publish("md", "1.0.0", "md");
        RecordingSource source = new RecordingSource(PluginSources.directory(repo));

        try (PluginService running = service(source)) {
            running.start();

            publish("csv", "2.0.0", "csv-v2");
            Files.delete(repo.resolve("md-1.0.0.jar"));
            publish("xml", "1.0.0", "xml");

            UpdateReport report = running.checkForUpdates();
            assertEquals(List.of(
                    new Change(Kind.UPDATED, "csv", "1.0.0", "2.0.0"),
                    new Change(Kind.REMOVED, "md", "1.0.0", null),
                    new Change(Kind.ADDED, "xml", null, "1.0.0")), report.changes());
            assertEquals(2, report.downloads(), "only the jars not cached yet");
            assertEquals(Set.of("csv-v1", "md"), formats(running), "running plugins are not touched");
        }

        source.offline = true;
        try (PluginService next = service(source)) {
            next.start();
            assertEquals(Set.of("csv-v2", "xml"), formats(next));
        }
    }

    @Test
    void aFailedUpdateLeavesTheInstalledSetAsItWas() {
        publish("csv", "1.0.0", "csv-v1");
        try (PluginService installer = service(PluginSources.directory(repo))) {
            installer.checkForUpdates();   // install without starting
        }

        publish("csv", "2.0.0", "csv-v2");
        try (PluginService updater = service(withWrongHashes(PluginSources.directory(repo)))) {
            PluginException e = assertThrows(PluginException.class, updater::checkForUpdates);
            assertTrue(e.getMessage().contains("Integrity check failed for csv@2.0.0"), e.getMessage());
        }

        try (PluginService plugins = PluginService.builder().cacheDirectory(cache).build()) {
            plugins.start();
            assertEquals(Set.of("csv-v1"), formats(plugins));
        }
    }

    @Test
    void theJarMustBeWhatTheSourceAdvertised() throws IOException {
        Path jar = publish("csv", "1.0.0", "csv");
        String sha = PluginSources.sha256(jar);
        PluginSource mislabelled = new PluginSource() {
            @Override
            public List<PluginArtifact> artifacts() {
                return List.of(new PluginArtifact("csv", "9.9.9", sha));
            }

            @Override
            public InputStream open(PluginArtifact artifact) throws IOException {
                return Files.newInputStream(jar);
            }
        };
        try (PluginService plugins = service(mislabelled)) {
            PluginException e = assertThrows(PluginException.class, plugins::start);
            assertTrue(e.getMessage().contains("manifest says csv@1.0.0"), e.getMessage());
        }
    }

    @Test
    void aSourceOffersOneVersionPerPlugin() {
        publish("csv", "1.0.0", "csv-v1");
        PluginJars.plugin("csv", "2.0.0").withProcessor()
                .source("acme.csv.Exporter", exporter("csv", "csv-v2")).buildInto(repo);

        try (PluginService plugins = service(PluginSources.directory(repo))) {
            PluginException e = assertThrows(PluginException.class, plugins::start);
            assertTrue(e.getMessage().contains("offered twice"), e.getMessage());
        }
    }

    @Test
    void aMissingJarIsRestoredButNeverUpgraded() {
        publish("csv", "1.0.0", "csv-v1");
        RecordingSource source = new RecordingSource(PluginSources.directory(repo));
        try (PluginService plugins = service(source)) {
            plugins.start();
        }

        deleteCachedJars();
        try (PluginService plugins = service(source)) {
            plugins.start();
            assertEquals(Set.of("csv-v1"), formats(plugins), "same version restored");
        }
        assertEquals(2, source.listings);
        assertEquals(2, source.downloads);

        deleteCachedJars();
        publish("csv", "2.0.0", "csv-v2");
        try (PluginService plugins = service(source)) {
            plugins.start();
            assertTrue(formats(plugins).isEmpty(), "v1 is gone from the source; v2 is not installed implicitly");
        }
    }

    @Test
    void keepsJarsInUseAndThePreviousGenerationOnly() {
        PluginSource source = PluginSources.directory(repo);
        String v1 = sha(publish("csv", "1.0.0", "csv-1"));
        String v2;
        String v3;
        try (PluginService running = service(source)) {
            running.start();
            v2 = sha(publish("csv", "2.0.0", "csv-2"));
            running.checkForUpdates();
            v3 = sha(publish("csv", "3.0.0", "csv-3"));
            running.checkForUpdates();
            assertEquals(Set.of(v1, v2, v3), cachedJars(), "v1 in use here, v2 previous, v3 current");
        }
        try (PluginService idle = service(source)) {
            idle.checkForUpdates();
        }
        assertEquals(Set.of(v3), cachedJars());
    }

    // --- helpers -------------------------------------------------------------------------------------

    private PluginService service(PluginSource source) {
        return PluginService.builder().source(source).cacheDirectory(cache).build();
    }

    private static Set<String> formats(PluginService plugins) {
        return plugins.roles(ReportExporter.class).stream().map(ReportExporter::format).collect(Collectors.toSet());
    }

    /** Puts {@code id@version} in the source directory, replacing any other version of the same plugin. */
    private Path publish(String id, String version, String format) {
        try (DirectoryStream<Path> old = Files.newDirectoryStream(repo, id + "-*.jar")) {
            for (Path jar : old) {
                Files.delete(jar);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return PluginJars.plugin(id, version).withProcessor()
                .source("acme." + id + ".Exporter", exporter(id, format)).buildInto(repo);
    }

    private static String exporter(String pkg, String format) {
        return """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "%s"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """.formatted(pkg, format);
    }

    private static String sha(Path jar) {
        try {
            return PluginSources.sha256(jar);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Set<String> cachedJars() {
        try (Stream<Path> files = Files.list(cache.resolve("objects"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jar"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteCachedJars() {
        for (String sha : cachedJars()) {
            try {
                Files.delete(cache.resolve("objects").resolve(sha + ".jar"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Counts calls, and can pretend the network is down. */
    private static final class RecordingSource implements PluginSource {
        private final PluginSource delegate;
        int listings;
        int downloads;
        boolean offline;

        RecordingSource(PluginSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<PluginArtifact> artifacts() throws IOException {
            if (offline) {
                throw new IOException("offline");
            }
            listings++;
            return delegate.artifacts();
        }

        @Override
        public InputStream open(PluginArtifact artifact) throws IOException {
            if (offline) {
                throw new IOException("offline");
            }
            downloads++;
            return delegate.open(artifact);
        }
    }

    /** Advertises every artifact under a wrong hash but serves the real bytes (a tampered catalog). */
    private static PluginSource withWrongHashes(PluginSource real) {
        Map<PluginArtifact, PluginArtifact> originals = new HashMap<>();
        return new PluginSource() {
            @Override
            public List<PluginArtifact> artifacts() throws IOException {
                return real.artifacts().stream().map(a -> {
                    PluginArtifact fake = new PluginArtifact(a.id(), a.version(), "0".repeat(64));
                    originals.put(fake, a);
                    return fake;
                }).toList();
            }

            @Override
            public InputStream open(PluginArtifact artifact) throws IOException {
                return real.open(originals.get(artifact));
            }
        };
    }
}
