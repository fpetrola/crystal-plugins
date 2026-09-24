package dev.crystal.plugins.runtime.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

import org.pf4j.JarPluginLoader;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stock {@link JarPluginLoader}, plus the third-party libraries a plugin carries in {@value #LIB}: each is
 * extracted once into the cache ({@code libs/<sha256>.jar}, shared by every plugin that carries the same bytes)
 * and added to that plugin's classloader.
 *
 * <p>A library the application already has is not loaded again: the application's copy is the one used, so an
 * object of that library means the same class in the application and in the plugin. Only what the application
 * lacks comes from the plugin.
 */
final class LibrariesPluginLoader extends JarPluginLoader {

    /** Where the build puts a plugin's libraries inside its jar. */
    static final String LIB = "lib/";

    private static final Logger log = LoggerFactory.getLogger(LibrariesPluginLoader.class);

    private final Path libs;
    private final ClassLoader application;

    LibrariesPluginLoader(PluginManager manager, Path libs) {
        super(manager);
        this.libs = libs;
        this.application = LibrariesPluginLoader.class.getClassLoader();
    }

    @Override
    public ClassLoader loadPlugin(Path pluginPath, PluginDescriptor descriptor) {
        PluginClassLoader loader = (PluginClassLoader) super.loadPlugin(pluginPath, descriptor);
        try (JarFile jar = new JarFile(pluginPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(LIB) || !name.endsWith(".jar") || name.indexOf('/', LIB.length()) >= 0) {
                    continue;
                }
                Path library = extract(jar, entry);
                String probe = firstClass(library);
                if (probe != null && application.getResource(probe) != null) {
                    log.debug("Plugin '{}': {} is already in the application; using the application's",
                            descriptor.getPluginId(), name);
                    continue;
                }
                loader.addFile(library.toFile());
            }
        } catch (IOException e) {
            throw new PluginRuntimeException(e, "Plugin '{}': cannot read its libraries", descriptor.getPluginId());
        }
        return loader;
    }

    private Path extract(JarFile jar, JarEntry entry) throws IOException {
        Files.createDirectories(libs);
        Path part = Files.createTempFile(libs, "lib", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(jar.getInputStream(entry), digest)) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Path target = libs.resolve(HexFormat.of().formatHex(digest.digest()) + ".jar");
            if (!Files.exists(target)) {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return target;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** The first class of a library, to ask the application whether it has it already. */
    private static String firstClass(Path library) throws IOException {
        try (JarInputStream in = new JarInputStream(Files.newInputStream(library))) {
            List<String> found = new ArrayList<>(1);
            for (JarEntry e; (e = in.getNextJarEntry()) != null; ) {
                String name = e.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class")) {
                    found.add(name);
                    break;
                }
            }
            return found.isEmpty() ? null : found.get(0);
        }
    }
}
