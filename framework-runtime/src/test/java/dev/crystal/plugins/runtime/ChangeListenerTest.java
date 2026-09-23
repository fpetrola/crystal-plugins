package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeListenerTest {

    @TempDir
    Path repo;

    @Test
    void everyChangeToWhatRunsOrIsInstalledIsNotified() throws Exception {
        for (String id : List.of("csv", "md")) {
            PluginJars.plugin(id, "1.0.0").withProcessor().source("acme." + id + ".Exporter", """
                    package acme.%s;
                    import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                    import java.util.List;
                    public class Exporter implements ReportExporter {
                        public String format() { return "%s"; }
                        public String export(List<String> rows) { return ""; }
                    }
                    """.formatted(id, id)).buildInto(repo);
        }
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            List<String> seen = new ArrayList<>();
            AutoCloseable subscription = plugins.onChange(() -> seen.add(String.valueOf(plugins.plugins().size())));
            plugins.onChange(() -> {
                throw new IllegalStateException("a broken listener does not stop the others");
            });

            plugins.start();
            plugins.install("csv");
            plugins.install("md");
            plugins.uninstallOnNextStart("md");
            plugins.uninstall("csv");
            assertEquals(List.of("0", "1", "2", "2", "1"), seen);

            subscription.close();
            plugins.install("md");
            assertEquals(5, seen.size(), "closed: no more notifications");
        }
    }
}
