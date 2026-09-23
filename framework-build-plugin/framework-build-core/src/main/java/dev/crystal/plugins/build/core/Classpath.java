package dev.crystal.plugins.build.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where every class visible to the plugin's compilation comes from: the plugin itself (its class directory,
 * plus anything else packed in its jar) or a classpath entry (first one wins, as for javac).
 *
 * <p>Plugins are recognized by their standard metadata, a {@code Plugin-Id} in the manifest, never by how
 * they were built.
 */
final class Classpath implements Closeable {

    /** A classpath entry that is a plugin. */
    record PluginIdentity(String id, String version) {
    }

    private final Path classesDirectory;
    private final ZipFile ownJar;
    private final Set<String> own = new HashSet<>();
    private final List<ClasspathEntry> entries;
    private final Map<String, ClasspathEntry> owners = new HashMap<>();
    private final Map<Path, ZipFile> zips = new LinkedHashMap<>();
    private final Map<ClasspathEntry, Optional<PluginIdentity>> identities = new HashMap<>();

    Classpath(Path classesDirectory, Path jar, List<ClasspathEntry> entries) throws IOException {
        this.classesDirectory = classesDirectory;
        this.ownJar = new ZipFile(jar.toFile());
        this.entries = List.copyOf(entries);
        try {
            own.addAll(classesIn(classesDirectory));
            ownJar.stream().map(ZipEntry::getName).map(Classpath::internalName).flatMap(Optional::stream).forEach(own::add);
            for (ClasspathEntry entry : this.entries) {
                for (String name : classesIn(entry.path())) {
                    owners.putIfAbsent(name, entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    List<ClasspathEntry> entries() {
        return entries;
    }

    /** Internal names of the classes compiled from the plugin's sources. */
    List<String> compiledClasses() throws IOException {
        return new ArrayList<>(classesIn(classesDirectory));
    }

    boolean isOwn(String internalName) {
        return own.contains(internalName);
    }

    Optional<ClasspathEntry> owner(String internalName) {
        return Optional.ofNullable(owners.get(internalName));
    }

    /** Bytes of a class: the plugin's own first, then the classpath. Empty for JDK or unknown classes. */
    Optional<byte[]> bytes(String internalName) throws IOException {
        String file = internalName + ".class";
        if (own.contains(internalName)) {
            Path compiled = classesDirectory.resolve(file);
            if (Files.isRegularFile(compiled)) {
                return Optional.of(Files.readAllBytes(compiled));
            }
            return read(ownJar, file);
        }
        ClasspathEntry entry = owners.get(internalName);
        if (entry == null) {
            return Optional.empty();
        }
        if (Files.isDirectory(entry.path())) {
            return Optional.of(Files.readAllBytes(entry.path().resolve(file)));
        }
        return read(zip(entry.path()), file);
    }

    /** The plugin an entry is, if it is one. */
    Optional<PluginIdentity> plugin(ClasspathEntry entry) {
        return identities.computeIfAbsent(entry, Classpath::identify);
    }

    /** The classpath entry that is the plugin {@code pluginId}, if any. */
    Optional<PluginIdentity> pluginById(String pluginId) {
        return entries.stream().map(this::plugin).flatMap(Optional::stream)
                .filter(p -> p.id().equals(pluginId)).findFirst();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        List<ZipFile> all = new ArrayList<>(zips.values());
        all.add(ownJar);
        for (ZipFile zip : all) {
            try {
                zip.close();
            } catch (IOException e) {
                failure = e;
            }
        }
        zips.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private ZipFile zip(Path path) throws IOException {
        ZipFile zip = zips.get(path);
        if (zip == null) {
            zip = new ZipFile(path.toFile());
            zips.put(path, zip);
        }
        return zip;
    }

    private Set<String> classesIn(Path path) throws IOException {
        Set<String> names = new HashSet<>();
        if (path == null || !Files.exists(path)) {
            return names;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.walk(path)) {
                files.filter(Files::isRegularFile)
                        .map(f -> internalName(path.relativize(f).toString().replace('\\', '/')))
                        .flatMap(Optional::stream)
                        .forEach(names::add);
            }
        } else {
            zip(path).stream().map(ZipEntry::getName).map(Classpath::internalName).flatMap(Optional::stream)
                    .forEach(names::add);
        }
        return names;
    }

    /** {@code a/b/C.class} → {@code a/b/C}; empty for resources, module/package descriptors, versioned entries. */
    private static Optional<String> internalName(String entryName) {
        if (!entryName.endsWith(".class") || entryName.startsWith("META-INF/")
                || entryName.endsWith("module-info.class") || entryName.endsWith("package-info.class")) {
            return Optional.empty();
        }
        return Optional.of(entryName.substring(0, entryName.length() - ".class".length()));
    }

    private static Optional<byte[]> read(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return Optional.of(in.readAllBytes());
        }
    }

    private static Optional<PluginIdentity> identify(ClasspathEntry entry) {
        try {
            Manifest manifest;
            if (Files.isDirectory(entry.path())) {
                Path file = entry.path().resolve(JarFile.MANIFEST_NAME);
                if (!Files.isRegularFile(file)) {
                    return Optional.empty();
                }
                try (InputStream in = Files.newInputStream(file)) {
                    manifest = new Manifest(in);
                }
            } else {
                try (JarFile jar = new JarFile(entry.path().toFile())) {
                    manifest = jar.getManifest();
                }
            }
            if (manifest == null) {
                return Optional.empty();
            }
            Attributes main = manifest.getMainAttributes();
            String id = main.getValue("Plugin-Id");
            String version = main.getValue("Plugin-Version");
            return id == null || version == null ? Optional.empty() : Optional.of(new PluginIdentity(id, version));
        } catch (IOException e) {
            throw new BuildException("Cannot read the manifest of " + entry.coordinates() + " (" + entry.path() + ")", e);
        }
    }
}
