package dev.crystal.plugins.runtime.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;
import dev.crystal.plugins.runtime.PluginException;

/**
 * The local plugin cache, the only place PF4J loads jars from.
 *
 * <pre>
 * &lt;cache&gt;/
 *   objects/&lt;sha256&gt;.jar   immutable, content-addressed; written once, after verification
 *   installed.tsv          the installed set: one "id  version  sha256" line per plugin
 * </pre>
 *
 * <p>Nothing is modified in place. A jar is never overwritten, because a classloader may have it open.
 * The installed set is replaced atomically, so a reader sees either the old set or the new one, never half
 * of each. An interrupted download leaves only a hidden {@code .part} file, which is cleaned up later.
 *
 * <p>The layout is an implementation detail, not a public contract: the public contract is the jar itself.
 */
public final class PluginCache {

    private static final Logger log = LoggerFactory.getLogger(PluginCache.class);

    private static final String HEADER = "crystal-installed\t1";
    private static final String PART_PREFIX = ".download-";
    private static final String PART_SUFFIX = ".part";
    private static final Duration STALE_PART = Duration.ofDays(1);

    private final Path root;
    private final Path objects;
    private final Path installedFile;

    public PluginCache(Path root) {
        this.root = root;
        this.objects = root.resolve("objects");
        this.installedFile = root.resolve("installed.tsv");
        try {
            Files.createDirectories(objects);
        } catch (IOException e) {
            throw new PluginException("Cannot create plugin cache in " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    public Path path(PluginArtifact artifact) {
        return objects.resolve(artifact.sha256() + ".jar");
    }

    public boolean contains(PluginArtifact artifact) {
        return Files.isRegularFile(path(artifact));
    }

    /**
     * The installed set, or empty when nothing was ever installed here. An unreadable file counts as
     * "never installed" (logged), so the next start reinstalls from the source instead of failing.
     */
    public Optional<List<PluginArtifact>> installed() {
        List<String> lines;
        try {
            lines = Files.readAllLines(installedFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Cannot read {}, treating the cache as empty", installedFile, e);
            return Optional.empty();
        }
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            log.warn("{} has an unknown format, treating the cache as empty", installedFile);
            return Optional.empty();
        }
        List<PluginArtifact> artifacts = new ArrayList<>();
        try {
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) {
                    throw new IllegalArgumentException("expected 3 tab-separated fields: " + line);
                }
                artifacts.add(new PluginArtifact(fields[0], fields[1], fields[2]));
            }
        } catch (IllegalArgumentException e) {
            log.warn("{} is corrupted ({}), treating the cache as empty", installedFile, e.getMessage());
            return Optional.empty();
        }
        return Optional.of(List.copyOf(artifacts));
    }

    /** Atomically replaces the installed set. */
    public void install(List<PluginArtifact> artifacts) throws IOException {
        StringBuilder content = new StringBuilder(HEADER).append('\n');
        for (PluginArtifact a : artifacts) {
            content.append(a.id()).append('\t').append(a.version()).append('\t').append(a.sha256()).append('\n');
        }
        Path tmp = Files.createTempFile(root, ".installed-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            force(tmp);
            Files.move(tmp, installedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Makes {@code artifact} available in the cache, reading it from {@code source} only if it is not there
     * yet. The bytes are checked against the advertised SHA-256 while they stream, and the jar's manifest
     * against the advertised id and version, before the file becomes visible.
     *
     * @return whether the artifact was downloaded (false: it was already cached)
     * @throws PluginException if the bytes do not match what the source advertised
     */
    public boolean fetch(PluginSource source, PluginArtifact artifact) throws IOException {
        Path target = path(artifact);
        if (Files.isRegularFile(target)) {
            return false;
        }
        Path part = Files.createTempFile(objects, PART_PREFIX, PART_SUFFIX);
        try {
            MessageDigest digest = sha256();
            try (InputStream in = new DigestInputStream(source.open(artifact), digest)) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(artifact.sha256())) {
                throw new PluginException("Integrity check failed for " + artifact.coordinates()
                        + ": expected sha256 " + artifact.sha256() + ", got " + actual);
            }
            checkManifest(part, artifact);
            force(part);
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                if (!Files.isRegularFile(target)) {
                    throw e;
                }
                // Another process sharing the cache stored the same object meanwhile; same hash, same bytes.
            }
            log.debug("Cached {} as {}", artifact.coordinates(), target.getFileName());
            return true;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * Deletes cached jars whose hash is not in {@code keep}, and downloads abandoned long ago. Deletion
     * failures (a jar still open on Windows, say) are ignored: the file is retried on the next prune.
     */
    public void prune(Set<String> keep) {
        Instant stale = Instant.now().minus(STALE_PART);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(objects)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean unused = name.endsWith(".jar") && !keep.contains(name.substring(0, name.length() - 4));
                boolean abandoned = name.startsWith(PART_PREFIX) && name.endsWith(PART_SUFFIX)
                        && Files.getLastModifiedTime(file).compareTo(FileTime.from(stale)) < 0;
                if (unused || abandoned) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.debug("Cannot delete {} now, will retry on the next prune", file, e);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Cannot prune plugin cache {}", objects, e);
        }
    }

    private static void checkManifest(Path jar, PluginArtifact artifact) throws IOException {
        Manifest manifest;
        try (JarFile file = new JarFile(jar.toFile())) {
            manifest = file.getManifest();
        } catch (IOException e) {
            throw new PluginException(artifact.coordinates() + " is not a readable jar", e);
        }
        Attributes main = manifest == null ? new Attributes() : manifest.getMainAttributes();
        String id = main.getValue("Plugin-Id");
        String version = main.getValue("Plugin-Version");
        if (!artifact.id().equals(id) || !artifact.version().equals(version)) {
            throw new PluginException("Source advertised " + artifact.coordinates() + " but the jar's manifest says "
                    + id + "@" + version);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }
}
