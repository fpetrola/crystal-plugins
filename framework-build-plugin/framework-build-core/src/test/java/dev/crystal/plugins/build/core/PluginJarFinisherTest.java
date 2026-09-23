package dev.crystal.plugins.build.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginJarFinisherTest {

    @TempDir
    Path dir;

    @Test
    void mergesManifestAndReplacesMetadata() throws IOException {
        Path jar = dir.resolve("p.jar");
        Manifest original = new Manifest();
        original.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        original.getMainAttributes().putValue("Created-By", "Maven JAR Plugin");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), original)) {
            out.putNextEntry(new JarEntry("p/A.class"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/plugin-metadata.json"));
            out.write("{\"format\":1}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        PluginJarFinisher.finish(jar, Map.of("Plugin-Id", "p", "Plugin-Version", "1.0.0"),
                "META-INF/plugin-metadata.json", "{\"format\":1,\"id\":\"p\"}\n");

        // JarInputStream only sees a manifest written as the first entries.
        try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
            Attributes main = in.getManifest().getMainAttributes();
            assertEquals("p", main.getValue("Plugin-Id"));
            assertEquals("1.0.0", main.getValue("Plugin-Version"));
            assertEquals("Maven JAR Plugin", main.getValue("Created-By"), "existing attributes are kept");
            List<String> names = new java.util.ArrayList<>();
            for (JarEntry e; (e = in.getNextJarEntry()) != null; ) {
                names.add(e.getName());
            }
            assertTrue(names.contains("p/A.class"));
        }
        assertEquals("{\"format\":1,\"id\":\"p\"}\n", PluginJarFinisher.read(jar, "META-INF/plugin-metadata.json"));
    }
}
