package dev.crystal.plugins.guice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.google.inject.util.Modules;

import dev.crystal.plugins.runtime.PluginException;
import dev.crystal.plugins.runtime.PluginJars;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.PluginSources;
import dev.crystal.plugins.runtime.fixtures.Peripheral;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

class PluginsModuleTest {

    @TempDir
    Path repo;

    /** An object of the application's own graph; nothing in it names the framework. */
    public static final class GameBrowser {
        final Set<ReportExporter> exporters;
        final Provider<ReportExporter> preferred;
        final String title;

        @Inject
        public GameBrowser(Set<ReportExporter> exporters, Provider<ReportExporter> preferred, String title) {
            this.exporters = exporters;
            this.preferred = preferred;
            this.title = title;
        }
    }

    /** Holds the preferred exporter as a fixed reference. */
    public static final class Printer {
        final ReportExporter exporter;

        @Inject
        public Printer(ReportExporter exporter) {
            this.exporter = exporter;
        }
    }

    private static final class AppModule extends AbstractModule {
        @Override
        protected void configure() {
            bind(String.class).toInstance("games");
        }
    }

    private void exporter(String id, String version) {
        PluginJars.plugin(id, version).withProcessor().source("acme." + id + ".Exporter", """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "%s"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """.formatted(id, id)).buildInto(repo);
    }

    private PluginService started() {
        PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build();
        plugins.start();
        return plugins;
    }

    private static Set<String> formats(Set<ReportExporter> exporters) {
        return exporters.stream().map(ReportExporter::format).collect(Collectors.toSet());
    }

    @Test
    void rolesAreDiscoveredFromTheIndexOnTheClassPath() {
        try (PluginService plugins = started()) {
            assertEquals(Set.of(ReportExporter.class, Peripheral.class), PluginsModule.of(plugins).roles());
        }
    }

    @Test
    void theApplicationsOwnInjectorGetsLivePlugins() {
        exporter("csv", "1.0.0");
        try (PluginService plugins = started()) {
            Injector injector = Guice.createInjector(new AppModule(), PluginsModule.of(plugins));
            GameBrowser browser = injector.getInstance(GameBrowser.class);

            assertEquals("games", browser.title);
            assertEquals(Set.of("csv"), formats(browser.exporters));

            exporter("md", "2.0.0");
            plugins.install("md");

            assertEquals(Set.of("csv", "md"), formats(browser.exporters), "the view follows plugins");
            assertEquals("md", browser.preferred.get().format(), "providers resolve on every get()");
        }
    }

    @Test
    void aFixedReferenceInTheApplicationsGraphPinsThePlugin() {
        exporter("csv", "1.0.0");
        try (PluginService plugins = started()) {
            Injector injector = Guice.createInjector(new AppModule(), PluginsModule.of(plugins));
            Printer printer = injector.getInstance(Printer.class);
            GameBrowser browser = injector.getInstance(GameBrowser.class);

            PluginException e = assertThrows(PluginException.class, () -> plugins.uninstall("csv"));
            assertTrue(e.getMessage().contains("an application object (" + Printer.class.getName() + ")"),
                    e.getMessage());
            assertEquals("csv", printer.exporter.format());
            assertTrue(browser.exporters.contains(printer.exporter), "sets and providers pin nothing");
        }
    }

    @Test
    void rolesThatConfigureTheInjectorAreTakenBeforeItExists() {
        PluginJars.plugin("board", "1.0.0").withProcessor().source("acme.board.Board", """
                package acme.board;
                import com.google.inject.Binder;
                import com.google.inject.name.Names;
                public class Board implements dev.crystal.plugins.guice.Contribution {
                    public void configure(Binder binder) {
                        binder.bind(String.class).annotatedWith(Names.named("board")).toInstance("spectrum 128");
                    }
                }
                """).buildInto(repo);
        try (PluginService plugins = started()) {
            Injector injector = plugins.building(() -> Guice.createInjector(
                    Modules.combine(plugins.snapshot(Contribution.class)), PluginsModule.of(plugins)));

            assertEquals("spectrum 128", injector.getInstance(Key.get(String.class, Names.named("board"))));
            PluginException e = assertThrows(PluginException.class, () -> plugins.uninstall("board"));
            assertTrue(e.getMessage().contains("holds an implementation of 'board'"), e.getMessage());
        }
    }

    @Test
    void explicitRolesMustBeRoles() {
        try (PluginService plugins = started()) {
            assertThrows(IllegalArgumentException.class, () -> PluginsModule.of(plugins, Runnable.class));
            assertEquals(Set.of(Peripheral.class), PluginsModule.of(plugins, Peripheral.class).roles());
            assertEquals(List.of(), List.copyOf(plugins.snapshot(Peripheral.class)));
        }
    }
}
