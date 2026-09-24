package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.runtime.fixtures.HostExporter;
import dev.crystal.plugins.runtime.fixtures.Journal;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;
import dev.crystal.plugins.runtime.fixtures.ReportScreen;

class ApplicationImplementationsTest {

    @TempDir
    Path repo;
    @TempDir
    Path application;

    private static String exporter(String pkg, String annotations) {
        return """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                %s
                public class Exporter implements ReportExporter {
                    public String format() { return "%s"; }
                    public String export(List<String> rows) { return ""; }
                }
                """.formatted(pkg, annotations, pkg);
    }

    /** Builds the service with a class path where the application declares HostExporter, as its build would. */
    private PluginService service(Journal journal) throws Exception {
        Files.createDirectories(application.resolve("META-INF/services"));
        Files.createDirectories(application.resolve("META-INF/crystal"));
        Files.writeString(application.resolve("META-INF/crystal/roles.idx"), ReportExporter.class.getName() + "\n");
        Files.writeString(application.resolve("META-INF/services/" + ReportExporter.class.getName()),
                HostExporter.class.getName() + "\n");
        ClassLoader loader = new URLClassLoader(new URL[] {application.toUri().toURL()}, getClass().getClassLoader());
        return withContext(loader, () -> PluginService.builder().source(PluginSources.directory(repo))
                .expose(Journal.class, journal).build());
    }

    private static <T> T withContext(ClassLoader loader, Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void whatTheApplicationBringsIsOneMoreImplementation() throws Exception {
        PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", exporter("csv", ""))
                .buildInto(repo);
        Journal journal = new Journal();
        try (PluginService plugins = service(journal)) {
            plugins.installAll();
            plugins.start();
            assertEquals(List.of("host start"), journal.entries(), "built once, with exposed services injected");

            assertEquals(List.of("csv", "host"), plugins.roles(ReportExporter.class).stream()
                    .map(ReportExporter::format).toList(), "a plugin is preferred to the application's own");
            assertEquals("csv", plugins.preferred(ReportExporter.class).format());
            assertEquals(2, plugins.create(ReportScreen.class).exporters().size());
            assertEquals(List.of("csv"), plugins.plugins().stream().map(PluginInfo::id).toList(),
                    "the application is not a plugin");

            plugins.uninstall("csv");
            assertEquals(List.of("host"), plugins.roles(ReportExporter.class).stream()
                    .map(ReportExporter::format).toList());
        }
        assertEquals(List.of("host start", "host stop"), journal.entries());
    }

    @Test
    void aPluginCanReplaceIt() throws Exception {
        PluginJars.plugin("fast", "1.0.0").withProcessor()
                .source("acme.fast.Exporter", exporter("fast", "@dev.crystal.plugins.api.Replaces(\"application\")"))
                .buildInto(repo);
        try (PluginService plugins = service(new Journal())) {
            plugins.installAll();
            plugins.start();
            assertEquals(List.of("fast"), plugins.roles(ReportExporter.class).stream()
                    .map(ReportExporter::format).toList());
            RoleInfo role = plugins.roleTree().stream()
                    .filter(r -> r.role().equals(ReportExporter.class.getName())).findFirst().orElseThrow();
            assertEquals(List.of(new RoleInfo.Implementation("fast", "acme.fast.Exporter", true),
                    new RoleInfo.Implementation(PluginService.APPLICATION, HostExporter.class.getName(), false)),
                    role.implementations(), "hidden, and the tree says so");
        }
    }

    @Test
    void canBeLeftOut() throws Exception {
        Files.createDirectories(application.resolve("META-INF/services"));
        Files.createDirectories(application.resolve("META-INF/crystal"));
        Files.writeString(application.resolve("META-INF/crystal/roles.idx"), ReportExporter.class.getName() + "\n");
        Files.writeString(application.resolve("META-INF/services/" + ReportExporter.class.getName()),
                HostExporter.class.getName() + "\n");
        ClassLoader loader = new URLClassLoader(new URL[] {application.toUri().toURL()}, getClass().getClassLoader());
        try (PluginService plugins = withContext(loader, () -> PluginService.builder()
                .applicationImplementations(false).expose(Journal.class, new Journal()).build())) {
            plugins.start();
            assertEquals(0, plugins.roles(ReportExporter.class).size());
        }
    }
}
