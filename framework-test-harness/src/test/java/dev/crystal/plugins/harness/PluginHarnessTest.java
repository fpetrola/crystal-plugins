package dev.crystal.plugins.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginJars;
import dev.crystal.plugins.runtime.fixtures.Bus;
import dev.crystal.plugins.runtime.fixtures.Peripheral;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;

/** Milestone 8: a plugin tested on its own, with the real runtime and no host application. */
class PluginHarnessTest {

    @TempDir
    Path dir;

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

    /** Like {@code mvn compile} on a plugin project: {@code <project>/target/classes}. */
    private Path compiled(String project, String className, String code) {
        return PluginJars.plugin(project, "1.0.0").withProcessor().source(className, code)
                .compileInto(dir.resolve(project).resolve("target").resolve("classes"));
    }

    @Test
    void runsThePluginUnderTestWithStandInsForTheHost() {
        Path classes = compiled("beeper", "acme.beeper.Beeper", BEEPER);

        try (PluginHarness harness = PluginHarness.builder().classes(classes).expose(Bus.class, new Bus()).start()) {
            Peripheral beeper = harness.one(Peripheral.class);

            assertEquals("beeper@3500000", beeper.name());
            assertEquals(0xBF, beeper.read(0xFE));
            assertEquals("beeper", harness.pluginId(), "the project directory names it");
            assertNotSame(getClass().getClassLoader(), beeper.getClass().getClassLoader(),
                    "its own classloader, as in production");
        }
    }

    @Test
    void pluginsItDependsOnAreLoadedAsPlugins() {
        Path multi = PluginJars.plugin("multi", "1.4.2").withProcessor()
                .source("acme.multi.Dialect", """
                        package acme.multi;
                        @dev.crystal.plugins.api.RoleInterface
                        public interface Dialect { String separator(); }
                        """)
                .source("acme.multi.MultiExporter", """
                        package acme.multi;
                        import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                        import jakarta.inject.Inject;
                        import java.util.*;
                        public class MultiExporter implements ReportExporter {
                            @Inject Set<Dialect> dialects;
                            public String format() { return "multi"; }
                            public String export(List<String> rows) {
                                return String.join(dialects.iterator().next().separator(), rows);
                            }
                        }
                        """).buildInto(dir);
        Path classes = PluginJars.plugin("tsv", "1.0.0").withProcessor().compileAgainst(multi)
                .source("acme.tsv.Tab", """
                        package acme.tsv;
                        public class Tab implements acme.multi.Dialect { public String separator() { return "\\t"; } }
                        """).compileInto(dir.resolve("tsv").resolve("target").resolve("classes"));

        try (PluginHarness harness = PluginHarness.builder().classes(classes).plugin(multi).start()) {
            assertEquals("a\tb", harness.one(ReportExporter.class).export(List.of("a", "b")),
                    "the sub-plugin under test feeds the role its parent defines");
            assertEquals(List.of("multi", "tsv"), harness.plugins().stream().map(PluginInfo::id).sorted().toList());
        }
    }

    @Test
    void aPluginThatDoesNotStartSaysWhy() {
        Path classes = compiled("broken", "acme.broken.Broken", """
                package acme.broken;
                import dev.crystal.plugins.runtime.fixtures.Peripheral;
                public class Broken implements Peripheral, dev.crystal.plugins.api.HasLifecycle {
                    public String name() { return "broken"; }
                    public int read(int port) { return 0; }
                    public void onStart() { throw new IllegalStateException("no device"); }
                }
                """);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PluginHarness.builder().classes(classes).start());
        assertTrue(e.getMessage().startsWith("Plugin 'broken' did not start"), e.getMessage());
    }

    @Test
    void classesCompiledWithoutTheProcessorAreReported() {
        Path classes = PluginJars.plugin("stale", "1.0.0").source("acme.stale.Beeper", BEEPER)
                .compileInto(dir.resolve("stale").resolve("target").resolve("classes"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PluginHarness.builder().classes(classes).expose(Bus.class, new Bus()).start());
        assertTrue(e.getMessage().contains("Rebuild from clean"), e.getMessage());
    }

    @Test
    void aBuiltJarCanBeTestedToo() {
        Path jar = PluginJars.plugin("beeper", "2.0.0").withProcessor().source("acme.beeper.Beeper", BEEPER)
                .buildInto(dir);

        try (PluginHarness harness = PluginHarness.builder().jar(jar).expose(Bus.class, new Bus()).start()) {
            assertEquals("beeper", harness.pluginId());
            assertEquals(0xFF, harness.one(Peripheral.class).read(0x1F));
        }
    }

    @Test
    void theClasspathsPluginsAreFoundByTheirManifest() throws IOException {
        Path plugin = PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", """
                package acme.csv;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                public class Exporter implements ReportExporter {
                    public String format() { return "csv"; }
                    public String export(List<String> rows) { return ""; }
                }
                """).buildInto(dir);
        Path library = dir.resolve("library.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(library); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.finish();
        }

        TestClasspath classpath = new TestClasspath(plugin + File.pathSeparator + library);

        assertEquals(List.of(new TestClasspath.PluginJar(plugin.toAbsolutePath().normalize(), "csv")),
                classpath.plugins());
    }
}
