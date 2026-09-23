package dev.crystal.plugins.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;

/** Ready-made local {@link PluginSource}s. Remote sources are the host application's business. */
public final class PluginSources {

    private static final Logger log = LoggerFactory.getLogger(PluginSources.class);

    private PluginSources() {
    }

    /**
     * Every {@code *.jar} in {@code directory} that declares a {@code Plugin-Id} and {@code Plugin-Version}.
     * Handy for development, tests and applications that ship their plugins next to the executable. Other
     * jars are skipped with a warning naming them: a plugin that "does not show up" is usually a jar built
     * without the plugin metadata, or a directory full of something else.
     */
    public static PluginSource directory(Path directory) {
        return new DirectorySource(directory);
    }

    /** SHA-256 of a file, lower-case hex. */
    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }

    private static final class DirectorySource implements PluginSource {
        private final Path directory;
        private final Map<PluginArtifact, Path> files = new ConcurrentHashMap<>();

        DirectorySource(Path directory) {
            this.directory = directory;
        }

        @Override
        public List<PluginArtifact> artifacts() throws IOException {
            List<PluginArtifact> result = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            files.clear();
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : jars) {
                    Attributes main = mainAttributes(jar);
                    String id = main == null ? null : main.getValue("Plugin-Id");
                    String version = main == null ? null : main.getValue("Plugin-Version");
                    if (id == null || version == null) {
                        ignored.add(jar.getFileName().toString());
                        continue;
                    }
                    PluginArtifact artifact = new PluginArtifact(id, version, sha256(jar));
                    files.put(artifact, jar);
                    result.add(artifact);
                }
            }
            if (!ignored.isEmpty()) {
                ignored.sort(null);
                log.warn("Ignored {} jar(s) in {} without Plugin-Id/Plugin-Version in their manifest: {}",
                        ignored.size(), directory, ignored);
            }
            return result;
        }

        @Override
        public InputStream open(PluginArtifact artifact) throws IOException {
            Path jar = files.get(artifact);
            if (jar == null) {
                throw new IOException("Unknown artifact " + artifact.coordinates() + " in " + directory);
            }
            return Files.newInputStream(jar);
        }

        private static Attributes mainAttributes(Path jar) throws IOException {
            try (JarFile file = new JarFile(jar.toFile())) {
                Manifest manifest = file.getManifest();
                return manifest == null ? null : manifest.getMainAttributes();
            }
        }

        @Override
        public String toString() {
            return "directory(" + directory + ")";
        }
    }
}
