package dev.crystal.plugins.build.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.api.RoleInterface;
import dev.crystal.plugins.build.core.PluginDependency.Source;

/** Milestone 5: dependencies from bytecode, pinned to the compiled version; metadata cross-check. */
class PluginPackagerTest {

    @TempDir
    Path dir;

    /** Shared API supplied by the host: framework-api, jakarta.inject, the app's role interfaces. */
    private ClasspathEntry frameworkApi;
    private ClasspathEntry inject;
    private ClasspathEntry appApi;
    /** Another plugin, built as 1.4.2, that defines a role for sub-plugins. */
    private ClasspathEntry multiPlugin;
    /** A plain library in compile scope. */
    private ClasspathEntry library;

    @BeforeEach
    void classpath() {
        frameworkApi = new ClasspathEntry(Fixtures.location(RoleInterface.class), "dev.crystal.plugins",
                "framework-api", "0.1.0", true);
        inject = new ClasspathEntry(Fixtures.location(jakarta.inject.Inject.class), "jakarta.inject",
                "jakarta.inject-api", "2.0.1", true);

        Path app = Fixtures.compile(dir.resolve("app-classes"), Map.of("app.Exporter", """
                package app;
                @dev.crystal.plugins.api.RoleInterface
                public interface Exporter { String format(); }
                """), List.of(frameworkApi.path()), false);
        appApi = new ClasspathEntry(Fixtures.jar(app, dir.resolve("app-api.jar"), Map.of()), "app", "app-api", "3.0",
                true);

        Path multi = Fixtures.compile(dir.resolve("multi-classes"), Map.of("acme.multi.Dialect", """
                package acme.multi;
                @dev.crystal.plugins.api.RoleInterface
                public interface Dialect { String separator(); }
                """), List.of(frameworkApi.path()), false);
        multiPlugin = new ClasspathEntry(Fixtures.jar(multi, dir.resolve("multi.jar"),
                Map.of("Plugin-Id", "multi", "Plugin-Version", "1.4.2")), "acme", "multi", "1.4.2", true);

        Path lib = Fixtures.compile(dir.resolve("lib-classes"), Map.of("lib.Strings", """
                package lib;
                public final class Strings { public static String tab() { return "\\t"; } }
                """), List.of(), false);
        library = new ClasspathEntry(Fixtures.jar(lib, dir.resolve("lib.jar"), Map.of()), "lib", "lib", "1.0", false);
    }

    @Test
    void pinsPluginDependenciesToTheVersionCompiledAgainst() throws IOException {
        Path jar = buildPlugin(Map.of(
                "acme.tsv.Tab", """
                        package acme.tsv;
                        public class Tab implements acme.multi.Dialect {
                            public String separator() { return lib.Strings.tab(); }
                        }
                        """,
                "acme.tsv.TsvExporter", """
                        package acme.tsv;
                        public class TsvExporter implements app.Exporter {
                            private final java.util.List<String> columns = new java.util.ArrayList<>();
                            public String format() { return "tsv" + columns.size(); }
                        }
                        """), true);

        PackagingResult result = PluginPackager.finish(request(jar, "2.0"));

        assertTrue(result.plugin());
        assertEquals("2.0.0", result.version());
        assertEquals(List.of(new PluginDependency("multi", "1.4.2", Source.BYTECODE, List.of("acme.multi.Dialect"))),
                result.dependencies(), "JDK and shared API types are not dependencies; the other plugin is, pinned");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("lib:lib:1.0") && result.warnings().get(0).contains("lib.Strings"),
                result.warnings().get(0));

        try (JarFile file = new JarFile(jar.toFile())) {
            var main = file.getManifest().getMainAttributes();
            assertEquals("tsv", main.getValue("Plugin-Id"));
            assertEquals("2.0.0", main.getValue("Plugin-Version"));
            assertEquals("multi@1.4.2", main.getValue("Plugin-Dependencies"));
        }
        String metadata = PluginJarFinisher.read(jar, PluginPackager.METADATA);
        assertTrue(metadata.replaceAll("\\s+", "").contains("\"dependencies\":[{\"id\":\"multi\","
                + "\"version\":\"1.4.2\",\"source\":\"bytecode\",\"types\":[\"acme.multi.Dialect\"]}]"), metadata);
    }

    @Test
    void aLibraryTheBuildMarksIsCarriedInLib() throws IOException {
        Path jar = buildPlugin(Map.of("acme.tsv.Tab", """
                package acme.tsv;
                public class Tab implements acme.multi.Dialect {
                    public String separator() { return lib.Strings.tab(); }
                }
                """), true);
        ClasspathEntry carried = new ClasspathEntry(library.path(), "lib", "lib", "1.0", false, true);
        PackagingResult result = PluginPackager.finish(new PluginBuildRequest(dir.resolve("classes"), jar, "tsv",
                "2.0", "acme", "tsv", null, List.of(frameworkApi, inject, appApi, multiPlugin, carried)));

        assertEquals(List.of("carries 1 library in lib/: lib-1.0"), result.warnings(),
                "no \"neither a plugin nor provided\" warning: it travels inside");
        try (JarFile file = new JarFile(jar.toFile())) {
            assertTrue(file.getJarEntry("lib/lib-1.0.jar") != null);
            assertTrue(file.getJarEntry("acme/tsv/Tab.class") != null, "the plugin's own classes stay");
        }
        PluginPackager.finish(new PluginBuildRequest(dir.resolve("classes"), jar, "tsv", "2.0", "acme", "tsv", null,
                List.of(frameworkApi, inject, appApi, multiPlugin, carried)));
        try (JarFile file = new JarFile(jar.toFile())) {
            assertEquals(1, java.util.Collections.list(file.entries()).stream()
                    .filter(e -> e.getName().equals("lib/lib-1.0.jar")).count(), "finishing twice does not duplicate");
        }
    }

    @Test
    void needsIsPinnedWhenThePluginIsOnTheClasspath() {
        Path jar = buildPlugin(Map.of("acme.tsv.TsvExporter", """
                package acme.tsv;
                @dev.crystal.plugins.api.Needs({"multi", "ghost"})
                public class TsvExporter implements app.Exporter { public String format() { return "tsv"; } }
                """), true);

        PackagingResult result = PluginPackager.finish(request(jar, "1.0.0"));

        assertEquals(List.of(
                new PluginDependency("ghost", null, Source.NEEDS, List.of()),
                new PluginDependency("multi", "1.4.2", Source.NEEDS, List.of())), result.dependencies());
    }

    @Test
    void classesTheProcessorDidNotSeeFailTheBuild() {
        Path jar = buildPlugin(Map.of("acme.tsv.TsvExporter", """
                package acme.tsv;
                public class TsvExporter implements app.Exporter { public String format() { return "tsv"; } }
                """), false);

        BuildException e = assertThrows(BuildException.class, () -> PluginPackager.finish(request(jar, "1.0.0")));
        assertTrue(e.getMessage().contains("plugin-metadata.json is missing") && e.getMessage().contains("clean"),
                e.getMessage());
    }

    @Test
    void staleMetadataFailsTheBuild() throws IOException {
        Path classes = Fixtures.compile(dir.resolve("classes"), Map.of(
                "acme.tsv.A", "package acme.tsv; public class A implements app.Exporter { public String format() { return \"a\"; } }",
                "acme.tsv.B", "package acme.tsv; public class B implements app.Exporter { public String format() { return \"b\"; } }"),
                compileClasspath(), true);
        // B was deleted from the sources; an incremental build without the processor left its metadata behind.
        Files.delete(classes.resolve("acme/tsv/B.class"));
        // ... and C was added, compiled without the processor.
        Fixtures.compile(classes, Map.of("acme.tsv.C",
                "package acme.tsv; public class C implements app.Exporter { public String format() { return \"c\"; } }"),
                withClasses(classes), false);
        Path jar = Fixtures.jar(classes, dir.resolve("tsv.jar"), Map.of());

        BuildException e = assertThrows(BuildException.class, () -> PluginPackager.finish(request(jar, "1.0.0")));
        assertTrue(e.getMessage().contains("extensions not indexed: [acme.tsv.C]"), e.getMessage());
        assertTrue(e.getMessage().contains("indexed but no longer extensions: [acme.tsv.B]"), e.getMessage());
    }

    @Test
    void anIndexWrittenByPf4jsOwnProcessorFailsTheBuild() throws IOException {
        Path classes = Fixtures.compile(dir.resolve("classes"), Map.of("acme.tsv.TsvExporter", """
                package acme.tsv;
                public class TsvExporter implements app.Exporter { public String format() { return "tsv"; } }
                """), compileClasspath(), true);
        // What happens when pf4j is on the compile classpath: its processor creates the index first (empty).
        Files.writeString(classes.resolve("META-INF/extensions.idx"), "# Generated by PF4J\n");
        Path jar = Fixtures.jar(classes, dir.resolve("tsv.jar"), Map.of());

        BuildException e = assertThrows(BuildException.class, () -> PluginPackager.finish(request(jar, "1.0.0")));
        assertTrue(e.getMessage().contains("does not list [acme.tsv.TsvExporter]") && e.getMessage().contains("pf4j"),
                e.getMessage());
    }

    @Test
    void roleImplementationsThatAreNotExtensionsDoNotTripTheCrossCheck() throws IOException {
        Path jar = buildPlugin(Map.of(
                "acme.tsv.TsvExporter", """
                        package acme.tsv;
                        public class TsvExporter implements app.Exporter {
                            public String format() { return "tsv"; }
                            app.Exporter anonymous() { return new app.Exporter() { public String format() { return "x"; } }; }
                            app.Exporter local() {
                                class Local implements app.Exporter { public String format() { return "l"; } }
                                return new Local();
                            }
                        }
                        """,
                "acme.tsv.Bracketed", """
                        package acme.tsv;
                        public class Bracketed implements app.Exporter {
                            private final app.Exporter inner;
                            public Bracketed(app.Exporter inner) { this.inner = inner; }
                            public String format() { return "[" + inner.format() + "]"; }
                        }
                        """,
                "acme.tsv.Internal", """
                        package acme.tsv;
                        class Internal implements app.Exporter { public String format() { return "i"; } }
                        """,
                "acme.tsv.Holder", """
                        package acme.tsv;
                        public class Holder {
                            public class Inner implements app.Exporter { public String format() { return "n"; } }
                            public static class Nested implements app.Exporter { public String format() { return "s"; } }
                        }
                        """), true);

        PackagingResult result = PluginPackager.finish(request(jar, "1.0.0"));

        assertTrue(result.plugin());
        String metadata = PluginJarFinisher.read(jar, PluginPackager.METADATA);
        assertTrue(metadata.contains("\"class\": \"acme.tsv.TsvExporter\""), metadata);
        assertTrue(metadata.contains("\"class\": \"acme.tsv.Holder$Nested\""), metadata);
        assertFalse(metadata.contains("Bracketed") || metadata.contains("Internal") || metadata.contains("$Inner")
                || metadata.contains("$1") || metadata.contains("Local"), metadata);
    }

    @Test
    void modulesWithoutRoleImplementationsAreLeftUntouched() throws IOException {
        Path jar = buildPlugin(Map.of("acme.api.Formats", """
                package acme.api;
                @dev.crystal.plugins.api.RoleInterface
                public interface Formats { String name(); }
                """), true);
        byte[] before = Files.readAllBytes(jar);

        PackagingResult result = PluginPackager.finish(request(jar, "1.0.0"));

        assertFalse(result.plugin());
        assertArrayEquals(before, Files.readAllBytes(jar));
    }

    // --- helpers -------------------------------------------------------------------------------------

    private List<Path> compileClasspath() {
        return List.of(frameworkApi.path(), inject.path(), appApi.path(), multiPlugin.path(), library.path());
    }

    private List<Path> withClasses(Path classes) {
        List<Path> classpath = new ArrayList<>(compileClasspath());
        classpath.add(classes);
        return classpath;
    }

    private Path buildPlugin(Map<String, String> sources, boolean processor) {
        Path classes = Fixtures.compile(dir.resolve("classes"), sources, compileClasspath(), processor);
        return Fixtures.jar(classes, dir.resolve("tsv.jar"), Map.of());
    }

    private PluginBuildRequest request(Path jar, String version) {
        return new PluginBuildRequest(dir.resolve("classes"), jar, "tsv", version, "acme", "tsv", "TSV output",
                List.of(frameworkApi, inject, appApi, multiPlugin, library));
    }
}
