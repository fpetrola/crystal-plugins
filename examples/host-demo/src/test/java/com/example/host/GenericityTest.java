package com.example.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

import com.example.emulator.Clock;
import com.example.emulator.Peripheral;
import com.example.reports.ReportExporter;

import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.PluginSources;
import jakarta.inject.Inject;

/** The same framework, unchanged, serving two apps whose only link to it is "@RoleInterface". */
class GenericityTest {

    private static final Path PLUGINS = Path.of(System.getProperty("plugins.dir"));

    /** Emulator side: an app object that consumes peripherals by injection. */
    public static final class IoBus {
        private final Set<Peripheral> peripherals;

        @Inject
        public IoBus(Set<Peripheral> peripherals) {
            this.peripherals = peripherals;
        }

        int in(int port) {
            return peripherals.stream().filter(p -> p.handles(port)).findFirst().map(p -> p.in(port)).orElse(0xFF);
        }

        void out(int port, int value) {
            peripherals.stream().filter(p -> p.handles(port)).forEach(p -> p.out(port, value));
        }
    }

    @Test
    void emulatorApp() {
        Clock clock = () -> 69_888L;
        try (PluginService plugins = PluginService.builder()
                .source(PluginSources.directory(PLUGINS.resolve("emulator")))
                .expose(Clock.class, clock)
                .build()) {
            plugins.start();

            assertEquals(List.of("plugin-beeper"), plugins.plugins().stream().map(PluginInfo::id).toList());
            IoBus bus = plugins.create(IoBus.class);
            assertEquals(0xBF, bus.in(0xFE));
            bus.out(0xFE, 0x10);
            assertEquals(0xFF, bus.in(0xFE));
            assertEquals(0xFF, bus.in(0x1F), "no peripheral on that port");
        }
    }

    @Test
    void reportsApp() {
        try (PluginService plugins = PluginService.builder()
                .source(PluginSources.directory(PLUGINS.resolve("reports")))
                .build()) {
            plugins.start();

            Set<ReportExporter> exporters = plugins.roles(ReportExporter.class);
            Set<String> formats = new TreeSet<>();
            exporters.forEach(e -> formats.add(e.format()));
            assertEquals(Set.of("csv", "markdown"), formats);

            ReportExporter csv = exporters.stream().filter(e -> e.format().equals("csv")).findFirst().orElseThrow();
            assertEquals("a;b\n1;2", csv.export(List.of("a", "b"), List.of(List.of("1", "2"))),
                    "the sub-plugin feeds a role defined by another plugin");

            assertTrue(plugins.plugins().stream().allMatch(p -> p.status() == PluginInfo.Status.STARTED));
        }
    }

    @Test
    void pluginDependenciesAreDerivedByTheBuild() throws IOException {
        try (JarFile jar = new JarFile(PLUGINS.resolve("reports/plugin-csv-semicolon-1.0.0.jar").toFile())) {
            assertEquals("plugin-csv-exporter@1.0.0",
                    jar.getManifest().getMainAttributes().getValue("Plugin-Dependencies"),
                    "found in the bytecode, pinned to the version compiled against");
        }
    }

    @Test
    void hostTestDoublesStayDiscoverableWithServiceLoader() {
        List<String> found = ServiceLoader.load(ReportExporter.class).stream()
                .map(provider -> provider.type().getSimpleName()).toList();
        assertEquals(List.of("InMemoryExporter"), found);
    }

    @Test
    void pluginClassesAreNotOnTheApplicationClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.example.beeper.Beeper"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.example.csv.CsvExporter"));
    }
}
