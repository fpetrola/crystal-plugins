package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Milestone 5, runtime side: pinned dependencies (what the build writes) and how unmet ones are handled. */
class DependencyPinningTest {

    @TempDir
    Path repo;

    /** Jars compiled against, but not offered by the source. */
    @TempDir
    Path elsewhere;

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

    private static String peripheral(String pkg) {
        return """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.Peripheral;
                public class Device implements Peripheral {
                    public String name() { return "%s"; }
                    public int read(int port) { return 0; }
                }
                """.formatted(pkg, pkg);
    }

    private Map<String, PluginInfo> start(PluginService plugins) {
        plugins.installAll();
        plugins.start();
        return plugins.plugins().stream().collect(Collectors.toMap(PluginInfo::id, p -> p));
    }

    @Test
    void aStalePinRejectsTheDependentNeverTheDependency() {
        Path csv = PluginJars.plugin("csv", "2.0.0").withProcessor()
                .source("acme.csv.Exporter", exporter("csv", "csv")).buildInto(repo);
        PluginJars.plugin("md", "1.0.0").withProcessor().dependsOn("csv@2.0.0", csv)
                .source("acme.md.Exporter", exporter("md", "md")).buildInto(repo);
        Path printer = PluginJars.plugin("printer", "1.0.0").withProcessor().dependsOn("csv@1.0.0", csv)
                .source("acme.printer.Device", peripheral("printer")).buildInto(repo);
        PluginJars.plugin("spooler", "1.0.0").withProcessor().dependsOn("printer", printer)
                .source("acme.spooler.Device", peripheral("spooler")).buildInto(repo);

        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            Map<String, PluginInfo> byId = start(plugins);

            assertEquals(PluginInfo.Status.STARTED, byId.get("csv").status(), "the dependency is healthy");
            assertEquals(PluginInfo.Status.STARTED, byId.get("md").status(), "and so are its other dependents");

            assertEquals(PluginInfo.Status.FAILED, byId.get("printer").status());
            assertEquals("requires csv@1.0.0 but 2.0.0 is installed", byId.get("printer").failure().orElseThrow()
                    .getMessage());
            assertEquals(PluginInfo.Status.FAILED, byId.get("spooler").status());
            assertTrue(byId.get("spooler").failure().orElseThrow().getMessage()
                    .startsWith("requires plugin 'printer', which was rejected: requires csv@1.0.0"));
            assertTrue(plugins.roles(dev.crystal.plugins.runtime.fixtures.Peripheral.class).isEmpty());
        }
    }

    @Test
    void preReleasePinsAreExact() {
        Path csv = PluginJars.plugin("csv", "1.0.0-SNAPSHOT").withProcessor()
                .source("acme.csv.Exporter", exporter("csv", "csv")).buildInto(repo);
        PluginJars.plugin("same", "1.0.0").withProcessor().dependsOn("csv@1.0.0-SNAPSHOT", csv)
                .source("acme.same.Device", peripheral("same")).buildInto(repo);
        PluginJars.plugin("release", "1.0.0").withProcessor().dependsOn("csv@1.0.0", csv)
                .source("acme.release.Device", peripheral("release")).buildInto(repo);

        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            Map<String, PluginInfo> byId = start(plugins);

            assertEquals(PluginInfo.Status.STARTED, byId.get("same").status());
            assertEquals(PluginInfo.Status.FAILED, byId.get("release").status(), "1.0.0-SNAPSHOT is not 1.0.0");
        }
    }

    @Test
    void aMissingDependencyIsReported() {
        Path csv = PluginJars.plugin("csv", "1.0.0").withProcessor()
                .source("acme.csv.Exporter", exporter("csv", "csv")).buildInto(elsewhere);
        PluginJars.plugin("printer", "1.0.0").withProcessor().dependsOn("csv", csv)
                .source("acme.printer.Device", peripheral("printer")).buildInto(repo);

        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            Map<String, PluginInfo> byId = start(plugins);

            assertEquals(PluginInfo.Status.FAILED, byId.get("printer").status());
            assertEquals("requires plugin 'csv', which is not installed",
                    byId.get("printer").failure().orElseThrow().getMessage());
        }
    }
}
