package dev.crystal.plugins.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import dev.crystal.plugins.build.processor.RoleProcessor;

/**
 * Builds plugin jars inside tests, standing in for an author's build.
 *
 * <p>{@link #withProcessor()} runs the real role processor (what the build plugin does); otherwise the
 * extension index is written by hand, proving the runtime only depends on standard files.
 */
final class PluginJars {

    private final String id;
    private final String version;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final List<String> dependencies = new ArrayList<>();
    private final List<Path> classpath = new ArrayList<>();
    private final List<String> manualIndex = new ArrayList<>();
    private boolean processor;
    private String pluginClass = "dev.crystal.plugins.runtime.internal.RolePlugin";

    private PluginJars(String id, String version) {
        this.id = id;
        this.version = version;
    }

    static PluginJars plugin(String id, String version) {
        return new PluginJars(id, version);
    }

    PluginJars source(String className, String code) {
        sources.put(className, code);
        return this;
    }

    PluginJars dependsOn(String pluginId, Path jar) {
        dependencies.add(pluginId);
        classpath.add(jar);
        return this;
    }

    PluginJars withProcessor() {
        processor = true;
        return this;
    }

    /** Hand-written extension index entry (manual path). */
    PluginJars indexed(String className) {
        manualIndex.add(className);
        return this;
    }

    PluginJars pluginClass(String pluginClass) {
        this.pluginClass = pluginClass;
        return this;
    }

    Path buildInto(Path directory) {
        try {
            Path classes = Files.createTempDirectory(directory, id + "-classes");
            compile(classes);
            if (!processor) {
                Path index = classes.resolve("META-INF/extensions.idx");
                Files.createDirectories(index.getParent());
                Files.writeString(index, String.join("\n", manualIndex) + "\n");
            }
            Path jar = directory.resolve(id + "-" + version + ".jar");
            writeJar(classes, jar);
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void compile(Path output) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<JavaFileObject> units = new ArrayList<>();
            sources.forEach((name, code) -> units.add(new SimpleJavaFileObject(
                    java.net.URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return code;
                }
            }));
            String cp = System.getProperty("java.class.path");
            for (Path extra : classpath) {
                cp += java.io.File.pathSeparator + extra;
            }
            List<String> options = List.of("--release", "21", "-d", output.toString(), "-cp", cp,
                    processor ? "-proc:full" : "-proc:none");
            JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics, options, null, units);
            if (processor) {
                task.setProcessors(List.of(new RoleProcessor()));
            }
            if (!task.call()) {
                throw new IllegalStateException("Compilation of plugin '" + id + "' failed: " + diagnostics.getDiagnostics());
            }
        }
    }

    private void writeJar(Path classes, Path jar) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Plugin-Id", id);
        main.putValue("Plugin-Version", version);
        if (pluginClass != null) {
            main.putValue("Plugin-Class", pluginClass);
        }
        if (!dependencies.isEmpty()) {
            main.putValue("Plugin-Dependencies", String.join(", ", dependencies));
        }
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out, manifest);
             Stream<Path> paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                jarOut.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jarOut);
                jarOut.closeEntry();
            }
        }
    }
}
