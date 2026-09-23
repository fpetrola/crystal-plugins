package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.ConflictResolver;
import dev.crystal.plugins.runtime.fixtures.Peripheral;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

/** Milestone 6: @Replaces (reversible) and the ConflictResolver. */
class ConflictResolutionTest {

    @TempDir
    Path repo;

    /** An application object: one exporter fixed at creation, and one looked up on every call. */
    public static final class Printer {
        final ReportExporter fixed;
        final Provider<ReportExporter> current;

        @Inject
        public Printer(ReportExporter fixed, Provider<ReportExporter> current) {
            this.fixed = fixed;
            this.current = current;
        }
    }

    private static String exporter(String pkg, String format, String annotations) {
        return """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                %s
                public class Exporter implements ReportExporter {
                    public String format() { return "%s"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """.formatted(pkg, annotations, format);
    }

    private Path plugin(String id, String version, String annotations) {
        return PluginJars.plugin(id, version).withProcessor()
                .source("acme." + id + ".Exporter", exporter(id, id, annotations)).buildInto(repo);
    }

    private PluginService service() {
        PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build();
        plugins.installAll();
        return plugins;
    }

    private static List<String> formats(PluginService plugins) {
        return plugins.roles(ReportExporter.class).stream().map(ReportExporter::format).toList();
    }

    @Test
    void aReplacedImplementationIsHiddenFromEveryViewButKeepsRunning() {
        plugin("csv", "1.0.0", "");
        plugin("fast", "1.0.0", "@dev.crystal.plugins.api.Replaces(\"csv\")");

        try (PluginService plugins = service()) {
            plugins.start();

            assertEquals(List.of("fast"), formats(plugins));
            assertEquals("fast", plugins.create(Printer.class).fixed.format());
            Map<String, PluginInfo.Status> status = plugins.plugins().stream()
                    .collect(Collectors.toMap(PluginInfo::id, PluginInfo::status));
            assertEquals(PluginInfo.Status.STARTED, status.get("csv"), "hidden, not stopped: ready to come back");
        }
    }

    @Test
    void onlyTheRolesBothImplementAreReplaced() {
        PluginJars.plugin("csv", "1.0.0").withProcessor()
                .source("acme.csv.Exporter", exporter("csv", "csv", ""))
                .source("acme.csv.Device", """
                        package acme.csv;
                        public class Device implements dev.crystal.plugins.runtime.fixtures.Peripheral {
                            public String name() { return "csv-device"; }
                            public int read(int port) { return 0; }
                        }
                        """).buildInto(repo);
        plugin("fast", "1.0.0", "@dev.crystal.plugins.api.Replaces(\"csv\")");

        try (PluginService plugins = service()) {
            plugins.start();

            assertEquals(List.of("fast"), formats(plugins));
            assertEquals(List.of("csv-device"), plugins.roles(Peripheral.class).stream().map(Peripheral::name).toList());
        }
    }

    @Test
    void aReplacementArrivingLiveHidesTheOriginalAtOnce() {
        plugin("csv", "1.0.0", "");
        try (PluginService plugins = service()) {
            plugins.start();
            var exporters = plugins.roles(ReportExporter.class);
            assertEquals(List.of("csv"), exporters.stream().map(ReportExporter::format).toList());

            plugin("fast", "1.0.0", "@dev.crystal.plugins.api.Replaces(\"csv\")");
            plugins.install("fast");

            assertEquals(List.of("fast"), exporters.stream().map(ReportExporter::format).toList());
        }
    }

    @Test
    void aReplacementThatFailsToStartReplacesNothing() {
        plugin("csv", "1.0.0", "");
        PluginJars.plugin("fast", "1.0.0").withProcessor().source("acme.fast.Exporter", """
                package acme.fast;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                @dev.crystal.plugins.api.Replaces("csv")
                public class Exporter implements ReportExporter, dev.crystal.plugins.api.HasLifecycle {
                    public String format() { return "fast"; }
                    public String export(List<String> rows) { return ""; }
                    public void onStart() { throw new IllegalStateException("no turbo today"); }
                }
                """).buildInto(repo);

        try (PluginService plugins = service()) {
            plugins.start();
            assertEquals(List.of("csv"), formats(plugins));
        }
    }

    @Test
    void theHighestVersionIsPreferredAndProvidersFollowChanges() {
        plugin("csv", "1.0.0", "");
        plugin("md", "2.0.0", "");
        try (PluginService plugins = service()) {
            plugins.start();
            Printer printer = plugins.create(Printer.class);
            assertEquals(List.of("md", "csv"), formats(plugins));
            assertEquals("md", printer.fixed.format());

            plugin("xml", "3.0.0", "");
            plugins.install("xml");

            assertEquals("xml", printer.current.get().format(), "a Provider resolves on every get()");
            assertEquals("md", printer.fixed.format(), "a plain injection was resolved once");
        }
    }

    @Test
    void theApplicationCanDecide() {
        plugin("csv", "1.0.0", "");
        plugin("md", "2.0.0", "");
        ConflictResolver onlyCsv = (role, all) -> all.stream().filter(i -> i.pluginId().equals("csv")).toList();

        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo))
                .conflictResolver(onlyCsv).build()) {
            plugins.installAll();
            plugins.start();

            assertEquals(List.of("csv"), formats(plugins));
            assertEquals("csv", plugins.create(Printer.class).fixed.format());
        }
    }
}
