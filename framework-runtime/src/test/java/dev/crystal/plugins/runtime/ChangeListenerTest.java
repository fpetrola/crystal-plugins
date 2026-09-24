package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void beforeUnloadComesWhileThePluginsClassesStillLoadDependentsFirst() throws Exception {
        Path base = PluginJars.plugin("looks", "1.0.0").withProcessor()
                .source("acme.looks.Exporter", """
                        package acme.looks;
                        import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                        import java.util.List;
                        public class Exporter implements ReportExporter {
                            public String format() { return "looks"; }
                            public String export(List<String> rows) { return ""; }
                        }
                        """)
                .source("acme.looks.Painter", "package acme.looks; public class Painter { }")
                .source("acme.looks.Unused", "package acme.looks; public class Unused { }")
                .source("acme.looks.Painter2", "package acme.looks; public class Painter2 { }")
                .buildInto(repo);
        PluginJars.plugin("theme", "1.0.0").withProcessor().dependsOn("looks@1.0.0", base)
                .source("acme.theme.Exporter", """
                        package acme.theme;
                        import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                        import java.util.List;
                        public class Exporter implements ReportExporter {
                            public String format() { return new acme.looks.Painter() != null ? "theme" : ""; }
                            public String export(List<String> rows) { return ""; }
                        }
                        """).buildInto(repo);
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            plugins.installAll();
            plugins.start();
            ClassLoader looks = plugins.roles(dev.crystal.plugins.runtime.fixtures.ReportExporter.class).stream()
                    .filter(e -> e.format().equals("looks")).findFirst().orElseThrow().getClass().getClassLoader();
            List<String> seen = new ArrayList<>();
            plugins.onChange(() -> seen.add("changed"));
            plugins.beforeUnload(id -> {
                try {
                    looks.loadClass("acme.looks.Unused"); // a class nobody loaded yet: the loader is still open
                    seen.add(id);
                } catch (ClassNotFoundException e) {
                    seen.add(id + " too late");
                }
            });
            plugins.uninstall("looks");
            assertEquals(List.of("theme", "looks", "changed"), seen);
            assertThrows(ClassNotFoundException.class, () -> looks.loadClass("acme.looks.Painter2"),
                    "after the unload, too late: which is why the notice comes before");
        }
    }
}
