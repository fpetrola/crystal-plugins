package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.runtime.fixtures.Journal;
import dev.crystal.plugins.runtime.fixtures.Peripheral;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

/** Milestone 7: nested scopes for sub-plugins, transitive unload of clean plugins. */
class SubPluginsAndUnloadTest {

    @TempDir
    Path repo;

    @TempDir
    Path cache;

    private final Journal journal = new Journal();

    /** An application object holding a fixed reference to an exporter. */
    public static final class Fixed {
        final ReportExporter exporter;

        @Inject
        public Fixed(ReportExporter exporter) {
            this.exporter = exporter;
        }
    }

    /** An application object that only looks exporters up when it needs one. */
    public static final class Looked {
        final Provider<ReportExporter> exporter;
        final Set<ReportExporter> all;

        @Inject
        public Looked(Provider<ReportExporter> exporter, Set<ReportExporter> all) {
            this.exporter = exporter;
            this.all = all;
        }
    }

    /** multi (defines the Dialect role) &lt;- tsv (a Dialect, plus a device using multi's object) &lt;- deep. */
    private void family() {
        Path multi = PluginJars.plugin("multi", "1.0.0").withProcessor()
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
                        public class MultiExporter implements ReportExporter, dev.crystal.plugins.api.HasLifecycle {
                            @Inject Set<Dialect> dialects;
                            @Inject Journal journal;
                            public String format() { return "multi@" + System.identityHashCode(this); }
                            public String export(List<String> rows) { return String.join(",", rows); }
                            public void onStop() { journal.log("stop multi"); }
                        }
                        """).buildInto(repo);
        Path tsv = PluginJars.plugin("tsv", "1.0.0").withProcessor().dependsOn("multi", multi)
                .source("acme.tsv.Tab", """
                        package acme.tsv;
                        import dev.crystal.plugins.runtime.fixtures.Journal;
                        import jakarta.inject.Inject;
                        public class Tab implements acme.multi.Dialect, dev.crystal.plugins.api.HasLifecycle {
                            @Inject Journal journal;
                            public String separator() { return "\\t"; }
                            public void onStop() { journal.log("stop tsv"); }
                        }
                        """)
                .source("acme.tsv.TabDevice", """
                        package acme.tsv;
                        import jakarta.inject.Inject;
                        public class TabDevice implements dev.crystal.plugins.runtime.fixtures.Peripheral {
                            @Inject acme.multi.MultiExporter parent;
                            public String name() { return "parent@" + System.identityHashCode(parent); }
                            public int read(int port) { return 0; }
                        }
                        """).buildInto(repo);
        PluginJars.plugin("deep", "1.0.0").withProcessor().dependsOn("tsv", tsv).compileAgainst(multi)
                .source("acme.deep.Deep", """
                        package acme.deep;
                        import dev.crystal.plugins.runtime.fixtures.Journal;
                        import jakarta.inject.Inject;
                        public class Deep implements dev.crystal.plugins.runtime.fixtures.Peripheral,
                                dev.crystal.plugins.api.HasLifecycle {
                            @Inject Journal journal;
                            public String name() { return "deep" + new acme.tsv.Tab().separator(); }
                            public int read(int port) { return 0; }
                            public void onStop() { journal.log("stop deep"); }
                        }
                        """).buildInto(repo);
    }

    private void exporter(String id, String annotations) {
        PluginJars.plugin(id, "1.0.0").withProcessor().source("acme." + id + ".Exporter", """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                %s
                public class Exporter implements ReportExporter {
                    public String format() { return "%s"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """.formatted(id, annotations, id)).buildInto(repo);
    }

    private PluginService service() {
        return PluginService.builder().source(PluginSources.directory(repo)).cacheDirectory(cache)
                .expose(Journal.class, journal).build();
    }

    private static List<String> formats(PluginService plugins) {
        return plugins.roles(ReportExporter.class).stream().map(ReportExporter::format).toList();
    }

    private static Set<String> ids(PluginService plugins) {
        return plugins.plugins().stream().map(PluginInfo::id).collect(Collectors.toSet());
    }

    @Test
    void aSubPluginSeesItsParentsObjects() {
        family();
        try (PluginService plugins = service()) {
            plugins.start();

            String parent = formats(plugins).stream().filter(f -> f.startsWith("multi@")).findFirst().orElseThrow();
            String seenByChild = plugins.roles(Peripheral.class).stream().map(Peripheral::name)
                    .filter(n -> n.startsWith("parent@")).findFirst().orElseThrow();
            assertEquals(parent.substring("multi@".length()), seenByChild.substring("parent@".length()),
                    "the same singleton, not a copy: the sub-plugin's scope is nested in its parent's");
        }
    }

    @Test
    void uninstallRemovesSubPluginsFirstAndNothingElse() {
        family();
        exporter("md", "");
        try (PluginService plugins = service()) {
            plugins.start();

            List<PluginInfo> removed = plugins.uninstall("multi");

            assertEquals(List.of("deep", "tsv", "multi"), removed.stream().map(PluginInfo::id).toList());
            assertTrue(removed.stream().allMatch(p -> p.status() == PluginInfo.Status.UNLOADED));
            assertEquals(List.of("stop deep", "stop tsv", "stop multi"), journal.entries(), "leaves first");
            assertEquals(List.of("md"), formats(plugins));
            assertEquals(Set.of("md"), ids(plugins));
        }
        try (PluginService next = PluginService.builder().cacheDirectory(cache).expose(Journal.class, journal).build()) {
            next.start();
            assertEquals(Set.of("md"), ids(next), "uninstalled for good");
        }
    }

    @Test
    void uninstallingAReplacementBringsTheOriginalBack() {
        exporter("csv", "");
        exporter("fast", "@dev.crystal.plugins.api.Replaces(\"csv\")");
        try (PluginService plugins = service()) {
            plugins.start();
            Set<ReportExporter> exporters = plugins.roles(ReportExporter.class);
            assertEquals(List.of("fast"), exporters.stream().map(ReportExporter::format).toList());

            plugins.uninstall("fast");

            assertEquals(List.of("csv"), exporters.stream().map(ReportExporter::format).toList());
        }
    }

    @Test
    void aPluginHeldByAnotherPluginIsNotUnloaded() {
        exporter("csv", "");
        PluginJars.plugin("printer", "1.0.0").withProcessor().source("acme.printer.Device", """
                package acme.printer;
                import dev.crystal.plugins.runtime.fixtures.*;
                import jakarta.inject.Inject;
                public class Device implements Peripheral {
                    private final ReportExporter exporter;
                    @Inject public Device(ReportExporter exporter) { this.exporter = exporter; }
                    public String name() { return "printer:" + exporter.format(); }
                    public int read(int port) { return 0; }
                }
                """).buildInto(repo);
        try (PluginService plugins = service()) {
            plugins.start();

            PluginException e = assertThrows(PluginException.class, () -> plugins.uninstall("csv"));

            assertTrue(e.getMessage().contains("plugin 'printer' holds an implementation of 'csv'"), e.getMessage());
            assertEquals(Set.of("csv", "printer"), ids(plugins), "nothing changed");
            assertEquals(List.of("printer"), plugins.uninstall("printer").stream().map(PluginInfo::id).toList());
            assertEquals(List.of("csv"), plugins.uninstall("csv").stream().map(PluginInfo::id).toList(),
                    "once its holder is gone, csv is clean");
        }
    }

    @Test
    void anApplicationObjectWithAFixedReferenceBlocksUninstallButLiveOnesDoNot() {
        exporter("csv", "");
        try (PluginService plugins = service()) {
            plugins.start();
            Looked looked = plugins.create(Looked.class);
            Fixed fixed = plugins.create(Fixed.class);

            PluginException e = assertThrows(PluginException.class, () -> plugins.uninstall("csv"));
            assertTrue(e.getMessage().contains("an application object (" + Fixed.class.getName() + ")"), e.getMessage());
            assertEquals("csv", fixed.exporter.format());
        }
        try (PluginService plugins = service()) {
            plugins.start();
            Looked looked = plugins.create(Looked.class);
            assertEquals("csv", looked.exporter.get().format());

            plugins.uninstall("csv");

            assertTrue(looked.all.isEmpty(), "views and providers never pin a plugin");
        }
    }

    @Test
    void uninstallingReleasesThePluginsClassLoader() throws InterruptedException {
        exporter("csv", "");
        try (PluginService plugins = service()) {
            plugins.start();
            WeakReference<ClassLoader> loader = loaderOfTheOnlyExporter(plugins);

            plugins.uninstall("csv");

            for (int i = 0; i < 100 && loader.get() != null; i++) {
                System.gc();
                Thread.sleep(20);
            }
            assertNull(loader.get(), "classloader and injector dropped together, nothing else holds them");
        }
    }

    @Test
    void onlyLoadedPluginsCanBeUninstalled() {
        try (PluginService plugins = service()) {
            plugins.start();
            assertThrows(IllegalArgumentException.class, () -> plugins.uninstall("ghost"));
        }
    }

    /** In its own frame, so no local variable of the test keeps the plugin reachable. */
    private static WeakReference<ClassLoader> loaderOfTheOnlyExporter(PluginService plugins) {
        return new WeakReference<>(plugins.roles(ReportExporter.class).iterator().next().getClass().getClassLoader());
    }
}
