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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;
import dev.crystal.plugins.runtime.fixtures.Peripheral;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;

/** install(pluginId): adding a plugin, and the dependencies it lacks, to a running service. */
class PluginInstallTest {

    @TempDir
    Path repo;

    @TempDir
    Path cache;

    /** Jars compiled against, but not offered by the source. */
    @TempDir
    Path elsewhere;

    @Test
    void aFreshServiceHasNothingInstalledUntilAsked() {
        exporter("md", "1.0.0", repo);
        try (PluginService plugins = service(PluginSources.directory(repo))) {
            plugins.start();
            assertTrue(plugins.plugins().isEmpty(), "the source is a catalog, not a list of what to install");
        }
    }

    @Test
    void installsAPluginAndTheDependenciesItLacksWhileRunning() {
        exporter("md", "1.0.0", repo);
        try (PluginService plugins = service(PluginSources.directory(repo))) {
            plugins.start();
            plugins.install("md");
            Set<Peripheral> devices = plugins.roles(Peripheral.class);
            Set<ReportExporter> exporters = plugins.roles(ReportExporter.class);
            assertTrue(devices.isEmpty());

            Path csv = exporter("csv", "1.0.0", repo);
            PluginJars.plugin("printer", "1.0.0").withProcessor().dependsOn("csv@1.0.0", csv)
                    .source("acme.printer.Device", device("printer")).buildInto(repo);

            List<PluginInfo> added = plugins.install("printer");

            assertEquals(List.of("csv", "printer"), added.stream().map(PluginInfo::id).toList(), "dependencies first");
            assertTrue(added.stream().allMatch(p -> p.status() == PluginInfo.Status.STARTED), added.toString());
            assertEquals(Set.of("printer"), names(devices), "views taken before the install see it");
            assertEquals(Set.of("csv", "md"), formats(exporters));
        }

        try (PluginService next = PluginService.builder().cacheDirectory(cache).build()) {
            next.start();
            assertEquals(Set.of("csv", "md", "printer"), ids(next), "installed for good, no source needed");
        }
    }

    @Test
    void aPlanThatDoesNotFitChangesNothing() {
        exporter("md", "1.0.0", repo);
        Path csv = exporter("csv", "1.0.0", elsewhere);
        PluginJars.plugin("printer", "1.0.0").withProcessor().dependsOn("csv@1.0.0", csv)
                .source("acme.printer.Device", device("printer")).buildInto(elsewhere);
        try (PluginService plugins = service(PluginSources.directory(repo))) {
            plugins.start();
            plugins.install("md");
            copy(elsewhere.resolve("printer-1.0.0.jar"), repo);   // offered, but its dependency is not

            PluginException e = assertThrows(PluginException.class, () -> plugins.install("printer"));

            assertTrue(e.getMessage().startsWith("'printer' requires plugin 'csv', which"), e.getMessage());
            assertEquals(Set.of("md"), ids(plugins));
        }
        try (PluginService next = PluginService.builder().cacheDirectory(cache).build()) {
            next.start();
            assertEquals(Set.of("md"), ids(next), "the installed set was not touched");
        }
    }

    @Test
    void aRunningPluginIsNeverReplaced() throws IOException {
        exporter("csv", "1.0.0", repo);
        try (PluginService plugins = service(PluginSources.directory(repo))) {
            plugins.start();
            plugins.install("csv");

            Files.delete(repo.resolve("csv-1.0.0.jar"));
            Path csv2 = exporter("csv", "2.0.0", repo);
            PluginJars.plugin("fancy", "1.0.0").withProcessor().dependsOn("csv@2.0.0", csv2)
                    .source("acme.fancy.Device", device("fancy")).buildInto(repo);

            PluginException e = assertThrows(PluginException.class, () -> plugins.install("fancy"));

            assertEquals("'fancy' requires csv@2.0.0 but csv 1.0.0 is running, and replacing a running plugin is "
                    + "not supported yet", e.getMessage());
            assertEquals(Set.of("csv"), ids(plugins));
        }
    }

    @Test
    void installingWhatIsAlreadyLoadedDoesNotTouchTheSource() {
        exporter("md", "1.0.0", repo);
        Switchable source = new Switchable(PluginSources.directory(repo));
        try (PluginService plugins = service(source)) {
            plugins.start();
            plugins.install("md");
            source.offline = true;

            assertEquals(List.of(), plugins.install("md"));
        }
    }

    @Test
    void installNeedsARunningService() {
        try (PluginService plugins = service(PluginSources.directory(repo))) {
            assertThrows(IllegalStateException.class, () -> plugins.install("md"));
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    private PluginService service(PluginSource source) {
        return PluginService.builder().source(source).cacheDirectory(cache).build();
    }

    private static Path exporter(String id, String version, Path into) {
        return PluginJars.plugin(id, version).withProcessor().source("acme." + id + ".Exporter", """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "%s"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """.formatted(id, id)).buildInto(into);
    }

    private static String device(String name) {
        return """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.Peripheral;
                public class Device implements Peripheral {
                    public String name() { return "%s"; }
                    public int read(int port) { return 0; }
                }
                """.formatted(name, name);
    }

    private static Set<String> ids(PluginService plugins) {
        return plugins.plugins().stream().map(PluginInfo::id).collect(Collectors.toSet());
    }

    private static Set<String> names(Set<Peripheral> devices) {
        return devices.stream().map(Peripheral::name).collect(Collectors.toSet());
    }

    private static Set<String> formats(Set<ReportExporter> exporters) {
        return exporters.stream().map(ReportExporter::format).collect(Collectors.toSet());
    }

    private static void copy(Path jar, Path directory) {
        try {
            Files.copy(jar, directory.resolve(jar.getFileName()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Can pretend the network is down. */
    private static final class Switchable implements PluginSource {
        private final PluginSource delegate;
        boolean offline;

        Switchable(PluginSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<PluginArtifact> artifacts() throws IOException {
            if (offline) {
                throw new IOException("offline");
            }
            return delegate.artifacts();
        }

        @Override
        public InputStream open(PluginArtifact artifact) throws IOException {
            if (offline) {
                throw new IOException("offline");
            }
            return delegate.open(artifact);
        }
    }
}
