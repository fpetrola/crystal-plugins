package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.runtime.fixtures.ReportExporter;

class PluginLibrariesTest {

    @TempDir
    Path repo;
    @TempDir
    Path elsewhere;
    @TempDir
    Path cache;

    @Test
    void aPluginUsesTheLibrariesItCarriesThatTheApplicationLacks() throws Exception {
        // A third-party library the application does not have.
        Path library = PluginJars.plugin("rest-lib", "1.0.0").pluginClass(null)
                .source("org.thirdparty.Rest", """
                        package org.thirdparty;
                        public class Rest { public static String call() { return "rest"; } }
                        """).buildInto(elsewhere);
        PluginJars.plugin("catalogue", "1.0.0").withProcessor().library(library)
                .source("acme.catalogue.Exporter", """
                        package acme.catalogue;
                        import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                        import java.util.List;
                        public class Exporter implements ReportExporter {
                            public String format() { return org.thirdparty.Rest.call(); }
                            public String export(List<String> rows) { return ""; }
                        }
                        """).buildInto(repo);

        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo))
                .cacheDirectory(cache).build()) {
            plugins.installAll();
            plugins.start();
            assertEquals("rest", plugins.roles(ReportExporter.class).iterator().next().format(),
                    "loaded from the plugin's lib/");
            try (var libs = Files.list(cache.resolve("libs"))) {
                assertTrue(libs.anyMatch(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.jar")),
                        "extracted once into the cache, by content");
            }
        }
    }

    @Test
    void aLibraryTheApplicationHasIsTheApplicationsCopy() throws Exception {
        // The plugin carries a jar with a class the application already has (the fixture role itself).
        Path library = PluginJars.plugin("copy", "1.0.0").pluginClass(null)
                .source("dev.crystal.plugins.runtime.fixtures.Journal", """
                        package dev.crystal.plugins.runtime.fixtures;
                        public final class Journal { public String copy() { return "plugin's copy"; } }
                        """).buildInto(elsewhere);
        PluginJars.plugin("user", "1.0.0").withProcessor().library(library)
                .source("acme.user.Exporter", """
                        package acme.user;
                        import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                        import java.util.List;
                        public class Exporter implements ReportExporter {
                            public String format() {
                                return dev.crystal.plugins.runtime.fixtures.Journal.class.getClassLoader()
                                        == ReportExporter.class.getClassLoader() ? "application" : "plugin";
                            }
                            public String export(List<String> rows) { return ""; }
                        }
                        """).buildInto(repo);
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo))
                .cacheDirectory(cache).build()) {
            plugins.installAll();
            plugins.start();
            assertEquals("application", plugins.roles(ReportExporter.class).iterator().next().format());
        }
    }

    @Test
    void aLibraryPluginLendsItsClassesToThePluginsThatDependOnIt() throws Exception {
        Path ide = PluginJars.plugin("device-ide", "1.0.0")
                .source("acme.ide.Channel", """
                        package acme.ide;
                        public class Channel { public static String name() { return "ide"; } }
                        """).buildInto(repo);
        PluginJars.plugin("device-divide", "1.0.0").withProcessor().dependsOn("device-ide@1.0.0", ide)
                .source("acme.divide.Exporter", """
                        package acme.divide;
                        import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                        import java.util.List;
                        public class Exporter implements ReportExporter {
                            public String format() { return "divide over " + acme.ide.Channel.name(); }
                            public String export(List<String> rows) { return ""; }
                        }
                        """).buildInto(repo);
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo))
                .cacheDirectory(cache).build()) {
            plugins.installAll();
            plugins.start();
            assertEquals("divide over ide", plugins.roles(ReportExporter.class).iterator().next().format());
            assertTrue(plugins.plugins().stream().allMatch(p -> p.status() == PluginInfo.Status.STARTED),
                    plugins.plugins().toString());
        }
    }

    @Test
    void whoAnswersAKeyIsFoundInstalledOrNot() throws Exception {
        for (String[] p : new String[][] {{"tape", "\"tap\", \"tzx\""}, {"disk", "\"dsk\""}}) {
            PluginJars.plugin(p[0], "1.0.0").withProcessor().source("acme." + p[0] + ".Exporter", """
                    package acme.%s;
                    import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                    import java.util.List;
                    @dev.crystal.plugins.api.Answers({%s})
                    public class Exporter implements ReportExporter {
                        public String format() { return "%s"; }
                        public String export(List<String> rows) { return ""; }
                    }
                    """.formatted(p[0], p[1], p[0])).buildInto(repo);
        }
        String role = ReportExporter.class.getName();
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo))
                .cacheDirectory(cache).build()) {
            plugins.start();
            assertEquals(List.of("tape"), plugins.availableAnswering(role, "TZX").stream().map(a -> a.id()).toList(),
                    "not installed: found through the catalog's description, ignoring case");
            assertEquals(List.of(), plugins.answering(role, "tzx"), "nothing installed answers it yet");
            plugins.install("tape");
            assertEquals(List.of("tape"), plugins.answering(role, "tzx"));
            assertEquals(List.of(), plugins.availableAnswering(role, "tzx"));
        }
    }

    @Test
    void whatIsOfferedIsFoundInstalledOrNot() throws Exception {
        PluginJars.plugin("catalogue", "1.0.0").withProcessor().source("acme.catalogue.Exporter", """
                package acme.catalogue;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                @dev.crystal.plugins.api.Offers("Browse the game catalogue")
                public class Exporter implements ReportExporter {
                    public String format() { return "catalogue"; }
                    public String export(List<String> rows) { return ""; }
                }
                """).buildInto(repo);
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo))
                .cacheDirectory(cache).build()) {
            plugins.start();
            assertEquals(List.of("Browse the game catalogue"), plugins.availableOffering().values().stream()
                    .flatMap(List::stream).map(dev.crystal.plugins.api.Offer::text).toList(),
                    "not installed: found through the catalog's description");
            assertEquals(List.of(), plugins.offering(), "nothing installed offers it yet");
            plugins.install("catalogue");
            assertEquals(List.of("Browse the game catalogue"),
                    plugins.offering().stream().map(dev.crystal.plugins.api.Offer::text).toList());
            assertEquals(Map.of(), plugins.availableOffering());
        }
    }
}
