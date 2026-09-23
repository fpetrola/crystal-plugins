package dev.crystal.plugins.build.core;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import dev.crystal.plugins.build.processor.RoleProcessor;

/** Compiles sources and packs jars, standing in for the author's build in tests. */
final class Fixtures {

    private Fixtures() {
    }

    /** Compiles {@code sources} (class name → code) into {@code output}, optionally with the role processor. */
    static Path compile(Path output, Map<String, String> sources, List<Path> classpath, boolean processor) {
        try {
            Files.createDirectories(output);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                List<JavaFileObject> units = new ArrayList<>();
                sources.forEach((name, code) -> units.add(new SimpleJavaFileObject(
                        URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return code;
                    }
                }));
                String cp = classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
                List<String> options = new ArrayList<>(List.of("--release", "21", "-d", output.toString(),
                        processor ? "-proc:full" : "-proc:none"));
                if (!cp.isEmpty()) {
                    options.addAll(List.of("-cp", cp));
                }
                JavaCompiler.CompilationTask task = javac.getTask(null, files, diagnostics, options, null, units);
                if (processor) {
                    task.setProcessors(List.of(new RoleProcessor()));
                }
                if (!task.call()) {
                    throw new IllegalStateException("Compilation failed: " + diagnostics.getDiagnostics());
                }
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Packs a class directory into a jar; {@code attributes} go to the manifest (none: no plugin identity). */
    static Path jar(Path classes, Path jar, Map<String, String> attributes) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach(manifest.getMainAttributes()::putValue);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out, manifest);
             Stream<Path> paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                jarOut.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jarOut);
                jarOut.closeEntry();
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Where a class was loaded from (a jar, or a class directory in a reactor build). */
    static Path location(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
