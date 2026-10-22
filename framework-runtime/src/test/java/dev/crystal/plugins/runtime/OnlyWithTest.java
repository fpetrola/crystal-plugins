package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.runtime.fixtures.Peripheral;

/**
 * An extension {@code @OnlyWith} another plugin: the plugin is there without it and leaves that extension out, and
 * the extension comes and goes with the other plugin, while the plugin stays.
 */
class OnlyWithTest {

    @TempDir
    Path repo;

    @TempDir
    Path cache;

    /** A printer that is always a device, and a second one only with the csv exporter, which it names itself after. */
    private void printerOnlyInPartWithCsv() {
        Path csv = PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", """
                package acme.csv;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "csv"; }
                    public String export(List<String> rows) { return String.join(",", rows); }
                }
                """).buildInto(repo);
        PluginJars.plugin("printer", "1.0.0").withProcessor().dependsOn("csv?@1.0.0", csv)
                .source("acme.printer.Device", """
                        package acme.printer;
                        import dev.crystal.plugins.runtime.fixtures.Peripheral;
                        public class Device implements Peripheral {
                            public String name() { return "printer"; }
                            public int read(int port) { return 0; }
                        }
                        """)
                .source("acme.printer.CsvDevice", """
                        package acme.printer;
                        import dev.crystal.plugins.runtime.fixtures.Peripheral;
                        @dev.crystal.plugins.api.OnlyWith("csv")
                        public class CsvDevice implements Peripheral {
                            public String name() { return "printer to " + new acme.csv.Exporter().format(); }
                            public int read(int port) { return 0; }
                        }
                        """).buildInto(repo);
    }

    @Test
    void withoutThatPluginTheRestIsThereAndItIsNotInstalledForIt() {
        printerOnlyInPartWithCsv();
        try (PluginService plugins = service()) {
            plugins.start();
            plugins.install("printer");

            assertEquals(Set.of("printer"), ids(plugins));
            assertEquals(Set.of("printer"), names(plugins.roles(Peripheral.class)));
        }
    }

    @Test
    void itComesWithThatPluginAndGoesWithItWhileThePluginStays() {
        printerOnlyInPartWithCsv();
        try (PluginService plugins = service()) {
            plugins.start();
            plugins.install("printer");
            Set<Peripheral> devices = plugins.roles(Peripheral.class);

            plugins.install("csv");
            assertEquals(Set.of("printer", "printer to csv"), names(devices));

            plugins.uninstall("csv");
            assertEquals(Set.of("printer"), ids(plugins));
            assertEquals(Set.of("printer"), names(devices));
        }
    }

    @Test
    void withThatPluginInstalledItIsThereFromTheStart() {
        printerOnlyInPartWithCsv();
        try (PluginService plugins = service()) {
            plugins.start();
            plugins.install("printer");
            plugins.install("csv");
        }
        try (PluginService next = PluginService.builder().cacheDirectory(cache).build()) {
            next.start();
            assertEquals(Set.of("printer", "printer to csv"), names(next.roles(Peripheral.class)));
        }
    }

    private PluginService service() {
        return PluginService.builder().source(PluginSources.directory(repo)).cacheDirectory(cache).build();
    }

    private static Set<String> ids(PluginService plugins) {
        return plugins.plugins().stream().map(PluginInfo::id).collect(Collectors.toSet());
    }

    private static Set<String> names(Set<Peripheral> devices) {
        return devices.stream().map(Peripheral::name).collect(Collectors.toSet());
    }
}
