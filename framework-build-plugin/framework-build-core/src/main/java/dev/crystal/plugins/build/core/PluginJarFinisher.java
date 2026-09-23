package dev.crystal.plugins.build.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Rewrites a built jar: merges attributes into its manifest and replaces {@code plugin-metadata.json}.
 *
 * <p>Done after the jar is built instead of configuring the jar plugin, so the author's
 * {@code maven-jar-plugin} setup (if any) stays untouched. The manifest is written first, as
 * {@link java.util.jar.JarInputStream} requires. Entry timestamps are preserved, so reproducible builds
 * stay reproducible.
 */
final class PluginJarFinisher {

    static final String MANIFEST = "META-INF/MANIFEST.MF";

    private PluginJarFinisher() {
    }

    /** Reads one entry as UTF-8, or {@code null} if absent. */
    static String read(Path jar, String entryName) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    static void finish(Path jar, Map<String, String> manifestAttributes, String metadataEntry, String metadata)
            throws IOException {
        Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".tmp");
        try (JarFile in = new JarFile(jar.toFile())) {
            Manifest manifest = in.getManifest() != null ? new Manifest(in.getManifest()) : new Manifest();
            Attributes main = manifest.getMainAttributes();
            main.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifestAttributes.forEach((k, v) -> main.put(new Attributes.Name(k), v));

            JarEntry oldManifest = in.getJarEntry(MANIFEST);
            JarEntry oldMetadata = in.getJarEntry(metadataEntry);
            long time = oldManifest != null ? oldManifest.getTime()
                    : oldMetadata != null ? oldMetadata.getTime() : System.currentTimeMillis();

            try (OutputStream raw = Files.newOutputStream(tmp); JarOutputStream out = new JarOutputStream(raw)) {
                putDirectory(out, "META-INF/", time);
                put(out, MANIFEST, time, manifestBytes(manifest));

                Enumeration<JarEntry> entries = in.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.equals("META-INF/") || name.equals(MANIFEST) || name.equals(metadataEntry)) {
                        continue;
                    }
                    JarEntry copy = new JarEntry(name);
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    try (InputStream data = in.getInputStream(entry)) {
                        data.transferTo(out);
                    }
                    out.closeEntry();
                }
                put(out, metadataEntry, time, metadata.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] manifestBytes(Manifest manifest) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        manifest.write(buffer);
        return buffer.toByteArray();
    }

    private static void putDirectory(JarOutputStream out, String name, long time) throws IOException {
        JarEntry dir = new JarEntry(name);
        dir.setTime(time);
        out.putNextEntry(dir);
        out.closeEntry();
    }

    private static void put(JarOutputStream out, String name, long time, byte[] data) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(time);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }
}
