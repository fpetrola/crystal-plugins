package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;
import dev.crystal.plugins.runtime.fixtures.Bus;
import dev.crystal.plugins.runtime.fixtures.Journal;
import dev.crystal.plugins.runtime.fixtures.Peripheral;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;
import dev.crystal.plugins.runtime.fixtures.ReportScreen;

class PluginServiceTest {

    @TempDir
    Path repo;

    private static final String BEEPER = """
            package acme.beeper;
            import dev.crystal.plugins.runtime.fixtures.*;
            import jakarta.inject.Inject;
            public class Beeper implements Peripheral {
                private final Bus bus;
                @Inject public Beeper(Bus bus) { this.bus = bus; }
                public String name() { return "beeper@" + bus.clock(); }
                public int read(int port) { return port == 0xFE ? 0xBF : 0xFF; }
            }
            """;

    private static final String CSV = """
            package acme.csv;
            import dev.crystal.plugins.runtime.fixtures.*;
            import java.util.List;
            public class CsvExporter implements ReportExporter {
                public String format() { return "csv"; }
                public String export(List<String> rows) { return String.join(",", rows); }
            }
            """;

    /** The directory is the list of plugins to have (drop-in folder): installAll() before start(). */
    private PluginService service(Journal journal) {
        PluginService plugins = PluginService.builder()
                .source(PluginSources.directory(repo))
                .expose(Bus.class, new Bus())
                .expose(Journal.class, journal)
                .build();
        plugins.installAll();
        return plugins;
    }

    @Test
    void sameFrameworkServesUnrelatedRoles() {
        PluginJars.plugin("beeper", "1.0.0").withProcessor().source("acme.beeper.Beeper", BEEPER).buildInto(repo);
        PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.CsvExporter", CSV).buildInto(repo);

        try (PluginService plugins = service(new Journal())) {
            plugins.start();

            Set<Peripheral> peripherals = plugins.roles(Peripheral.class);
            assertEquals(1, peripherals.size());
            Peripheral beeper = peripherals.iterator().next();
            assertEquals("beeper@3500000", beeper.name(), "host service injected into the plugin");
            assertEquals(0xBF, beeper.read(0xFE));

            ReportScreen screen = plugins.create(ReportScreen.class);
            assertEquals(Set.of("csv"), screen.exporters().stream().map(ReportExporter::format).collect(Collectors.toSet()));

            ClassLoader app = getClass().getClassLoader();
            ClassLoader beeperLoader = beeper.getClass().getClassLoader();
            ClassLoader csvLoader = screen.exporters().iterator().next().getClass().getClassLoader();
            assertNotSame(app, beeperLoader);
            assertNotSame(beeperLoader, csvLoader, "one classloader per plugin");
        }
    }

    @Test
    void handWrittenMetadataIsEnough() {
        // No processor, no Plugin-Class: manifest (id, version) + PF4J's extensions.idx, written by hand.
        PluginJars.plugin("csv", "2.0.0").pluginClass(null)
                .source("acme.csv.CsvExporter", CSV).indexed("acme.csv.CsvExporter")
                .buildInto(repo);

        try (PluginService plugins = service(new Journal())) {
            plugins.start();
            assertEquals("csv", plugins.roles(ReportExporter.class).iterator().next().format());
            assertEquals(List.of(new PluginInfo("csv", "2.0.0", PluginInfo.Status.STARTED, java.util.Optional.empty())),
                    plugins.plugins());
        }
    }

    @Test
    void lifecycleIsOptInAndViewsAreLive() {
        PluginJars.plugin("ticker", "1.0.0").withProcessor().source("acme.ticker.Ticker", """
                package acme.ticker;
                import dev.crystal.plugins.api.HasLifecycle;
                import dev.crystal.plugins.runtime.fixtures.*;
                import jakarta.inject.Inject;
                public class Ticker implements Peripheral, HasLifecycle {
                    @Inject Journal journal;
                    public String name() { return "ticker"; }
                    public int read(int port) { return 0; }
                    public void onStart() { journal.log("start"); }
                    public void onStop() { journal.log("stop"); }
                }
                """).buildInto(repo);

        Journal journal = new Journal();
        PluginService plugins = service(journal);
        ReportScreen screen;
        Set<Peripheral> peripherals;
        try (plugins) {
            peripherals = plugins.roles(Peripheral.class);
            screen = plugins.create(ReportScreen.class);
            assertTrue(peripherals.isEmpty(), "nothing started yet");

            plugins.start();
            assertEquals(1, peripherals.size(), "the same Set instance now sees the plugin");
            assertEquals(List.of("start"), journal.entries());
            assertTrue(screen.exporters().isEmpty());
        }
        assertEquals(List.of("start", "stop"), journal.entries());
        assertTrue(peripherals.isEmpty(), "stopped plugins disappear from live views");
    }

    @Test
    void pluginInjectsSingleRoleProvidedByItsDependency() {
        Path csv = PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.CsvExporter", CSV).buildInto(repo);
        PluginJars.plugin("printer", "1.0.0").withProcessor().dependsOn("csv", csv)
                .source("acme.printer.Printer", """
                        package acme.printer;
                        import dev.crystal.plugins.runtime.fixtures.*;
                        import jakarta.inject.Inject;
                        public class Printer implements Peripheral {
                            private final ReportExporter exporter;
                            @Inject public Printer(ReportExporter exporter) { this.exporter = exporter; }
                            public String name() { return "printer:" + exporter.format(); }
                            public int read(int port) { return 0; }
                        }
                        """).buildInto(repo);

        try (PluginService plugins = service(new Journal())) {
            plugins.start();
            assertEquals("printer:csv", plugins.roles(Peripheral.class).iterator().next().name());
        }
    }

    @Test
    void pluginsCanDefineRolesForSubPlugins() {
        Path host = PluginJars.plugin("multi", "1.0.0").withProcessor()
                .source("acme.multi.Dialect", """
                        package acme.multi;
                        @dev.crystal.plugins.api.RoleInterface
                        public interface Dialect { String separator(); }
                        """)
                .source("acme.multi.MultiExporter", """
                        package acme.multi;
                        import dev.crystal.plugins.runtime.fixtures.*;
                        import jakarta.inject.Inject;
                        import java.util.*;
                        public class MultiExporter implements ReportExporter {
                            @Inject Set<Dialect> dialects;
                            public String format() { return "multi" + dialects.size(); }
                            public String export(List<String> rows) {
                                return String.join(dialects.iterator().next().separator(), rows);
                            }
                        }
                        """).buildInto(repo);
        PluginJars.plugin("tsv", "1.0.0").withProcessor().dependsOn("multi", host)
                .source("acme.tsv.Tab", """
                        package acme.tsv;
                        public class Tab implements acme.multi.Dialect { public String separator() { return "\\t"; } }
                        """).buildInto(repo);

        try (PluginService plugins = service(new Journal())) {
            plugins.start();
            ReportExporter multi = plugins.roles(ReportExporter.class).iterator().next();
            assertEquals("multi1", multi.format(), "the plugin's own role is fed by its sub-plugin");
            assertEquals("a\tb", multi.export(List.of("a", "b")));
        }
    }

    @Test
    void failingPluginDoesNotTakeDownTheOthers() {
        PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.CsvExporter", CSV).buildInto(repo);
        PluginJars.plugin("broken", "1.0.0").withProcessor().source("acme.broken.Broken", """
                package acme.broken;
                import dev.crystal.plugins.api.HasLifecycle;
                import dev.crystal.plugins.runtime.fixtures.*;
                import java.util.List;
                public class Broken implements ReportExporter, HasLifecycle {
                    public String format() { return "broken"; }
                    public String export(List<String> rows) { return ""; }
                    public void onStart() { throw new IllegalStateException("no device"); }
                }
                """).buildInto(repo);

        try (PluginService plugins = service(new Journal())) {
            plugins.start();
            Map<String, PluginInfo> byId = plugins.plugins().stream()
                    .collect(Collectors.toMap(PluginInfo::id, p -> p));
            assertEquals(PluginInfo.Status.FAILED, byId.get("broken").status());
            assertTrue(byId.get("broken").failure().isPresent());
            assertEquals(PluginInfo.Status.STARTED, byId.get("csv").status());
            assertEquals(List.of("csv"), plugins.roles(ReportExporter.class).stream().map(ReportExporter::format).toList());
        }
    }

    @Test
    void rolesRequiresARoleInterface() {
        try (PluginService plugins = service(new Journal())) {
            assertThrows(IllegalArgumentException.class, () -> plugins.roles(Runnable.class));
        }
    }

    @Test
    void corruptedDownloadIsRejected() throws IOException {
        Path jar = PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.CsvExporter", CSV).buildInto(repo);
        PluginSource lying = new PluginSource() {
            @Override
            public List<PluginArtifact> artifacts() {
                return List.of(new PluginArtifact("csv", "1.0.0", "0".repeat(64)));
            }

            @Override
            public InputStream open(PluginArtifact artifact) throws IOException {
                return Files.newInputStream(jar);
            }
        };
        try (PluginService plugins = PluginService.builder().source(lying).build()) {
            PluginException e = assertThrows(PluginException.class, plugins::installAll);
            assertTrue(e.getMessage().contains("csv@1.0.0"), e.getMessage());
        }
    }
}
