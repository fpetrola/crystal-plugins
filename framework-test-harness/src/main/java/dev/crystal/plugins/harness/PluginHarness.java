package dev.crystal.plugins.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import dev.crystal.plugins.api.ConflictResolver;
import dev.crystal.plugins.build.core.PackagingResult;
import dev.crystal.plugins.build.core.PluginBuildRequest;
import dev.crystal.plugins.build.core.PluginPackager;
import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.PluginSources;

/**
 * Runs the plugin under test in a real, embedded {@link PluginService}, without the host application.
 *
 * <pre>{@code
 * try (PluginHarness harness = PluginHarness.builder()
 *         .expose(Clock.class, () -> 0L)          // stand-ins for the host's services
 *         .start()) {
 *     Peripheral beeper = harness.one(Peripheral.class);
 *     assertEquals(0xBF, beeper.in(0xFE));
 * }
 * }</pre>
 *
 * <p>Faithful to production: the plugin gets its own classloader and is wired as the runtime will wire it. Its
 * compiled classes ({@code target/classes} by default; tests run before {@code package}) are packaged on the fly by
 * the same {@link PluginPackager} the build uses: same bytecode analysis, same pinned dependencies, same check for
 * stale metadata. Plugins it depends on are taken from the test class path (jars with a {@code Plugin-Id}), and
 * loaded as plugins too.
 *
 * <p>Use the plugin through its roles: its classes are also on the test class path, but the instances come from
 * the plugin's own classloader, so a cast to a class of the plugin fails. Test-framework agnostic: problems are
 * thrown as {@link IllegalStateException}.
 */
public final class PluginHarness implements AutoCloseable {

    /** Version given to the plugin under test when it is packaged from its classes. */
    public static final String VERSION = "0.0.0-harness";

    private final PluginService service;
    private final Path workDirectory;
    private final String pluginId;

    private PluginHarness(PluginService service, Path workDirectory, String pluginId) {
        this.service = service;
        this.workDirectory = workDirectory;
        this.pluginId = pluginId;
    }

    /** The plugin in {@code target/classes}, no host services: {@code builder().start()}. */
    public static PluginHarness start() {
        return builder().start();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Live view of the visible implementations of {@code role}, as the application would get it. */
    public <T> Set<T> roles(Class<T> role) {
        return service.roles(role);
    }

    /**
     * The only visible implementation of {@code role}.
     *
     * @throws IllegalStateException if there is none, or more than one
     */
    public <T> T one(Class<T> role) {
        List<T> all = List.copyOf(roles(role));
        if (all.size() != 1) {
            throw new IllegalStateException("Expected one " + role.getSimpleName() + ", found " + all.size() + ": "
                    + all);
        }
        return all.get(0);
    }

    /** The id the plugin under test runs with. */
    public String pluginId() {
        return pluginId;
    }

    /** Every loaded plugin: the one under test and those it depends on. */
    public List<PluginInfo> plugins() {
        return service.plugins();
    }

    /** The embedded service, for anything else (install, uninstall, create...). */
    public PluginService service() {
        return service;
    }

    @Override
    public void close() {
        service.close();
        deleteRecursively(workDirectory);
    }

    /** Configures a {@link PluginHarness}. Everything is optional. */
    public static final class Builder {
        private Path classes = Path.of("target", "classes");
        private Path jar;
        private String pluginId;
        private final List<Path> plugins = new ArrayList<>();
        private final Map<Class<?>, Object> exposed = new LinkedHashMap<>();
        private ConflictResolver conflictResolver;

        private Builder() {
        }

        /** The compiled classes of the plugin under test (default {@code target/classes}). */
        public Builder classes(Path classes) {
            this.classes = Objects.requireNonNull(classes);
            this.jar = null;
            return this;
        }

        /** Tests an already built plugin jar instead (e.g. in integration tests, after {@code package}). */
        public Builder jar(Path jar) {
            this.jar = Objects.requireNonNull(jar);
            return this;
        }

        /** The id of the plugin under test when packaged from classes; default: the project directory's name. */
        public Builder pluginId(String pluginId) {
            this.pluginId = Objects.requireNonNull(pluginId);
            return this;
        }

        /** Another plugin jar to load next to the one under test (besides those found on the class path). */
        public Builder plugin(Path jar) {
            plugins.add(Objects.requireNonNull(jar));
            return this;
        }

        /** A stand-in for a host service, injectable in plugins. */
        public <T> Builder expose(Class<T> type, T instance) {
            exposed.put(Objects.requireNonNull(type), Objects.requireNonNull(instance));
            return this;
        }

        public Builder conflictResolver(ConflictResolver conflictResolver) {
            this.conflictResolver = Objects.requireNonNull(conflictResolver);
            return this;
        }

        /**
         * Packages (if needed) and starts the plugin under test.
         *
         * @throws IllegalStateException if it cannot be packaged or does not start; the message says why
         */
        public PluginHarness start() {
            Path work;
            try {
                work = Files.createTempDirectory("crystal-harness");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try {
                return start(work);
            } catch (RuntimeException e) {
                deleteRecursively(work);
                throw e;
            }
        }

        private PluginHarness start(Path work) {
            Path offered = work.resolve("plugins");
            TestClasspath classpath = TestClasspath.current();
            String id = underTest(offered, classpath);

            for (TestClasspath.PluginJar dependency : classpath.plugins()) {
                if (!dependency.id().equals(id)) {
                    copy(dependency.path(), offered);
                }
            }
            plugins.forEach(extra -> copy(extra, offered));

            PluginService.Builder builder = PluginService.builder().source(PluginSources.directory(offered));
            exposed.forEach((type, instance) -> expose(builder, type, instance));
            if (conflictResolver != null) {
                builder.conflictResolver(conflictResolver);
            }
            PluginService service = builder.build();
            try {
                service.installAll();
                service.start();
            } catch (RuntimeException e) {
                service.close();
                throw e;
            }
            PluginInfo info = service.plugins().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
            if (info == null || info.status() != PluginInfo.Status.STARTED) {
                String why = info == null ? "it was not loaded"
                        : info.failure().map(Throwable::getMessage).orElse(info.status().toString());
                IllegalStateException failure = new IllegalStateException("Plugin '" + id + "' did not start: " + why,
                        info == null ? null : info.failure().orElse(null));
                service.close();
                throw failure;
            }
            return new PluginHarness(service, work, id);
        }

        /** Puts the plugin under test in {@code offered} as a finished jar; returns its id. */
        private String underTest(Path offered, TestClasspath classpath) {
            try {
                Files.createDirectories(offered);
                if (jar != null) {
                    copy(jar, offered);
                    return manifestId(jar);
                }
                Path classesDirectory = classes.toAbsolutePath().normalize();
                if (!Files.isDirectory(classesDirectory)) {
                    throw new IllegalStateException(classesDirectory + " does not exist: compile the plugin first, or "
                            + "point the harness at its classes or jar");
                }
                String id = pluginId != null ? pluginId : defaultId(classesDirectory);
                Path packaged = offered.resolve(id + ".jar");
                zip(classesDirectory, packaged);
                // What the plugin was compiled against: the test class path, plus the plugins added by hand.
                List<dev.crystal.plugins.build.core.ClasspathEntry> compiledAgainst =
                        new ArrayList<>(classpath.entries(classesDirectory));
                plugins.forEach(extra -> compiledAgainst.add(TestClasspath.coordinates(extra.toAbsolutePath())));
                PackagingResult result = PluginPackager.finish(new PluginBuildRequest(classesDirectory, packaged, id,
                        VERSION, "harness", id, null, compiledAgainst));
                if (!result.plugin()) {
                    throw new IllegalStateException(classesDirectory + " holds no role extensions: was it compiled "
                            + "with the build plugin (or the role processor)?");
                }
                return id;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (dev.crystal.plugins.build.core.BuildException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> void expose(PluginService.Builder builder, Class<T> type, Object instance) {
            builder.expose(type, (T) instance);
        }
    }

    /** {@code <project>/target/classes} → {@code <project>}. */
    private static String defaultId(Path classes) {
        Path target = classes.getParent();
        Path project = target == null ? null : target.getParent();
        return project == null || project.getFileName() == null ? "plugin-under-test" : project.getFileName().toString();
    }

    private static String manifestId(Path jar) {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            String id = file.getManifest() == null ? null : file.getManifest().getMainAttributes().getValue("Plugin-Id");
            if (id == null) {
                throw new IllegalStateException(jar + " has no Plugin-Id: it is not a finished plugin jar");
            }
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void zip(Path directory, Path jar) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out);
             Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                jarOut.putNextEntry(new JarEntry(directory.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jarOut);
                jarOut.closeEntry();
            }
        }
    }

    private static void copy(Path jar, Path directory) {
        try {
            Files.createDirectories(directory);
            Files.copy(jar, directory.resolve(jar.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: a temporary directory
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
