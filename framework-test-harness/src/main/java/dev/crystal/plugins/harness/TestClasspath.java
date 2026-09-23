package dev.crystal.plugins.harness;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import dev.crystal.plugins.build.core.ClasspathEntry;

/** The class path of the running tests, as the harness needs it. */
final class TestClasspath {

    /** A jar on the class path that is a plugin (its manifest has a {@code Plugin-Id}). */
    record PluginJar(Path path, String id) {
    }

    private final List<Path> elements;

    TestClasspath(String classpath) {
        List<Path> paths = new ArrayList<>();
        for (String element : classpath.split(File.pathSeparator)) {
            if (!element.isBlank()) {
                paths.add(Path.of(element).toAbsolutePath().normalize());
            }
        }
        this.elements = List.copyOf(paths);
    }

    static TestClasspath current() {
        return new TestClasspath(System.getProperty("java.class.path"));
    }

    /** Every element but {@code excluded}, described for the packager; coordinates from pom.properties if any. */
    List<ClasspathEntry> entries(Path excluded) {
        List<ClasspathEntry> entries = new ArrayList<>();
        for (Path element : elements) {
            if (element.equals(excluded) || !Files.exists(element)) {
                continue;
            }
            // Everything on the test class path is visible to the plugin through the parent classloader, as the
            // host's own classes would be: provided.
            entries.add(coordinates(element));
        }
        return entries;
    }

    /** The jars on the class path that are plugins: what the plugin under test may depend on. */
    List<PluginJar> plugins() {
        List<PluginJar> plugins = new ArrayList<>();
        for (Path element : elements) {
            if (Files.isRegularFile(element) && element.toString().endsWith(".jar")) {
                String id = pluginId(element);
                if (id != null) {
                    plugins.add(new PluginJar(element, id));
                }
            }
        }
        return plugins;
    }

    private static String pluginId(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return null;
            }
            Attributes main = manifest.getMainAttributes();
            return main.getValue("Plugin-Version") == null ? null : main.getValue("Plugin-Id");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + jar, e);
        }
    }

    /** A class path element described for the packager: coordinates from its pom.properties, if any. */
    static ClasspathEntry coordinates(Path element) {
        if (Files.isRegularFile(element)) {
            try (JarFile jar = new JarFile(element.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                        Properties pom = new Properties();
                        try (InputStream in = jar.getInputStream(entry)) {
                            pom.load(in);
                        }
                        return new ClasspathEntry(element, pom.getProperty("groupId", "classpath"),
                                pom.getProperty("artifactId", element.getFileName().toString()),
                                pom.getProperty("version", "0"), true);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + element, e);
            }
        }
        return new ClasspathEntry(element, "classpath", element.getFileName().toString(), "0", true);
    }
}
