package dev.crystal.plugins.build.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Puts default plugins inside a host application: finds plugin jars in a Maven local repository and copies them,
 * with an index, under {@value #DIRECTORY} of the application's classes, so any packaging (jar, shade...) carries
 * them. At runtime {@code PluginSources.bundled()} reads them back.
 *
 * <p>A plugin is recognized by its standard metadata ({@code Plugin-Id} in the manifest). The version is given,
 * never "the latest": the same build always bundles the same jars.
 */
public final class PluginBundler {

    /** Where bundled jars go, relative to the classes directory. */
    public static final String DIRECTORY = "META-INF/crystal/bundled/";
    /** One line per plugin: {@code id <TAB> version <TAB> sha256 <TAB> file}. */
    public static final String INDEX = "META-INF/crystal/bundled.idx";

    /** A plugin put inside the application. */
    public record Bundled(String id, String version, String sha256, String file, String coordinates) {
    }

    private PluginBundler() {
    }

    /**
     * @param localRepository Maven local repository ({@code ~/.m2/repository})
     * @param groupId         plugins of this groupId or of any groupId under it ({@code com.acme} also takes
     *                        {@code com.acme.plugins})
     * @param version         the artifact version to take
     * @param exclude         {@code groupId:artifactId} never bundled (the application itself)
     * @param classes         the application's classes directory
     */
    public static List<Bundled> bundle(Path localRepository, String groupId, String version, String exclude,
                                       Path classes) {
        Path groupDirectory = localRepository.resolve(groupId.replace('.', '/'));
        Map<String, Bundled> byId = new TreeMap<>();
        if (!Files.isDirectory(groupDirectory)) {
            throw new BuildException("No artifacts of " + groupId + " in " + localRepository
                    + ": install the plugins (mvn install) before building the application");
        }
        Path target = classes.resolve(DIRECTORY);
        try (Stream<Path> files = Files.walk(groupDirectory)) {
            for (Path jar : files.filter(PluginBundler::isArtifactJar).sorted().toList()) {
                Path versionDirectory = jar.getParent();
                if (!versionDirectory.getFileName().toString().equals(version)) {
                    continue;
                }
                String artifactId = versionDirectory.getParent().getFileName().toString();
                if (!jar.getFileName().toString().equals(artifactId + "-" + version + ".jar")) {
                    continue; // sources, javadoc, tests...
                }
                String group = localRepository.relativize(versionDirectory.getParent().getParent()).toString()
                        .replace('\\', '/').replace('/', '.');
                String coordinates = group + ":" + artifactId + ":" + version;
                if ((group + ":" + artifactId).equals(exclude)) {
                    continue;
                }
                Attributes main = manifest(jar);
                String id = main == null ? null : main.getValue("Plugin-Id");
                String pluginVersion = main == null ? null : main.getValue("Plugin-Version");
                if (id == null || pluginVersion == null) {
                    continue;
                }
                if (byId.containsKey(id)) {
                    throw new BuildException("Plugin '" + id + "' found twice: " + byId.get(id).coordinates() + " and "
                            + coordinates);
                }
                Files.createDirectories(target);
                String file = artifactId + "-" + version + ".jar";
                String sha = copy(jar, target.resolve(file));
                byId.put(id, new Bundled(id, pluginVersion, sha, file, coordinates));
            }
        } catch (IOException e) {
            throw new BuildException("Cannot bundle plugins from " + groupDirectory + ": " + e.getMessage(), e);
        }
        writeIndex(classes.resolve(INDEX), byId.values());
        return List.copyOf(byId.values());
    }

    private static boolean isArtifactJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar");
    }

    private static Attributes manifest(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes();
        }
    }

    private static String copy(Path from, Path to) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(from), digest)) {
                Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeIndex(Path index, Iterable<Bundled> bundled) {
        List<String> lines = new ArrayList<>();
        lines.add("# Generated by crystal-plugins: id, version, sha256, file");
        for (Bundled b : bundled) {
            lines.add(b.id() + "\t" + b.version() + "\t" + b.sha256() + "\t" + b.file());
        }
        try {
            Files.createDirectories(index.getParent());
            Files.writeString(index, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BuildException("Cannot write " + index + ": " + e.getMessage(), e);
        }
    }
}
