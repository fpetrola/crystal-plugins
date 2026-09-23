package dev.crystal.plugins.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;
import dev.crystal.plugins.runtime.internal.CrystalPluginManager;
import dev.crystal.plugins.runtime.internal.PluginCache;
import dev.crystal.plugins.runtime.internal.PluginInstaller;
import dev.crystal.plugins.runtime.internal.PluginScopes;
import dev.crystal.plugins.runtime.internal.RoleRegistry;
import dev.crystal.plugins.runtime.internal.Roles;

/**
 * The single boundary between a host application and its plugins.
 *
 * <pre>{@code
 * try (PluginService plugins = PluginService.builder()
 *         .source(myRemoteCatalog)                // where jars come from (the app's code, may use the network)
 *         .cacheDirectory(appDataDir.resolve("plugins"))
 *         .expose(EventBus.class, bus)            // host services plugins may @Inject
 *         .build()) {
 *     plugins.start();                            // loads from the local cache; no network if all cached
 *     Set<ReportExporter> exporters = plugins.roles(ReportExporter.class);  // live view
 *     ReportScreen screen = plugins.create(ReportScreen.class);            // @Inject Set<ReportExporter>
 *     ...
 *     plugins.install("sna-reader");              // add one plugin (and what it lacks) now, no restart
 *     UpdateReport report = plugins.checkForUpdates();   // install what the source offers, from the next start
 * }
 * }</pre>
 *
 * <p>Internally: jars live in a local, content-addressed cache; PF4J loads them from there (one classloader
 * per plugin, dependency ordering, unload) and Guice wires them (one child injector per plugin). Neither
 * library appears in this class's API.
 */
public final class PluginService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);

    private final PluginCache cache;
    private final boolean ownsCache;
    private final PluginInstaller installer;
    private final CrystalPluginManager manager;
    private final RoleRegistry registry = new RoleRegistry();
    private final PluginScopes scopes;
    private List<PluginArtifact> active = List.of();
    private boolean started;
    private boolean closed;

    private PluginService(Builder builder) {
        this.ownsCache = builder.cacheDirectory == null;
        try {
            this.cache = new PluginCache(ownsCache
                    ? Files.createTempDirectory("crystal-plugins")
                    : builder.cacheDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a temporary plugin cache", e);
        }
        this.installer = new PluginInstaller(cache, builder.source);
        if (ownsCache && builder.source != null) {
            log.info("Temporary plugin cache: plugins are fetched from {} on every start "
                    + "(set cacheDirectory to keep them between runs)", builder.source);
        }

        Map<Class<?>, Object> exposed = builder.exposed;
        Injector root = Guice.createInjector(Stage.PRODUCTION, new AbstractModule() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            protected void configure() {
                // No just-in-time bindings anywhere in the tree: a JIT binding created in the root for a
                // plugin class would outlive the plugin and pin its classloader.
                binder().requireExplicitBindings();
                exposed.forEach((type, instance) -> bind((Class) type).toInstance(instance));
            }
        });

        this.manager = new CrystalPluginManager(cache.root());
        this.scopes = new PluginScopes(root, registry, manager::getExtensionClassNames);
        manager.bind(scopes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads and starts every installed plugin, from the local cache.
     *
     * <p>The {@link PluginSource} is not consulted when every installed plugin is cached; it is only used on
     * the first start on an empty cache (to install what it offers) and to restore a jar missing from the
     * cache (same version, never an upgrade).
     *
     * <p>A plugin that fails (unresolvable dependency, exception in {@code onStart}) is reported by
     * {@link #plugins()}; it does not prevent the others from starting.
     *
     * @throws PluginException if the first installation fails (source unavailable, integrity check...); the
     *                         service stays unstarted and {@code start()} may be retried
     */
    public synchronized void start() {
        checkOpen();
        if (started) {
            return;
        }
        List<PluginArtifact> installed = installer.prepare();
        started = true;
        active = installed;
        manager.usePlugins(installed.stream().map(cache::path).toList());
        manager.loadPlugins();
        manager.startPlugins();
        for (PluginWrapper plugin : manager.getPlugins()) {
            if (plugin.getFailedException() != null) {
                log.error("Plugin '{}' failed", plugin.getPluginId(), plugin.getFailedException());
            }
        }
    }

    /**
     * Installs what the {@link PluginSource} offers now: downloads the jars that are not cached yet (each
     * one once, verified by SHA-256) and records the new installed set. This is the only operation that
     * consults the source on purpose.
     *
     * <p>All-or-nothing: if anything fails, the installed set is left as it was. The new set takes effect the
     * next time a {@code PluginService} starts on this cache; plugins already running are not touched.
     * Called before {@link #start()}, it therefore refreshes the plugins this service is about to load.
     *
     * @throws IllegalStateException if no source is configured
     * @throws PluginException       if the source cannot be read or serves something other than it advertised
     */
    public synchronized UpdateReport checkForUpdates() {
        checkOpen();
        return installer.update(active.stream().map(PluginArtifact::sha256).collect(Collectors.toSet()));
    }

    /**
     * Installs {@code pluginId} from the {@link PluginSource}, together with the dependencies it lacks, and
     * starts them now: when this method returns, their role implementations are in every {@link #roles} view.
     * The plugins already running are not touched.
     *
     * <p>Dependencies come from the jars' own {@code Plugin-Dependencies}; each jar is downloaded once and
     * verified by SHA-256. A plugin already installed keeps its version, and a running plugin is never
     * replaced (that needs unloading). If anything does not fit, nothing is installed. The new plugins are
     * recorded in the installed set before they are loaded, so they are there on the next start too.
     *
     * @return the plugins this call added, dependencies first, with their status now (a plugin whose
     *         {@code onStart} throws is {@code FAILED}); empty if {@code pluginId} was already loaded
     * @throws IllegalStateException if the service is not started, or has no source
     * @throws PluginException       if the plugin or a dependency is not offered, a version does not fit, or a
     *                               download fails; nothing has changed then
     */
    public synchronized List<PluginInfo> install(String pluginId) {
        checkOpen();
        if (!started) {
            throw new IllegalStateException("install() adds plugins to a running service; call start() first "
                    + "(before starting, checkForUpdates() installs what the source offers)");
        }
        if (manager.getPlugin(pluginId) != null) {
            return List.of();
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        manager.getPlugins().forEach(p -> loaded.put(p.getPluginId(), p.getDescriptor().getVersion()));
        List<PluginArtifact> plan = installer.plan(pluginId, loaded, manager.getVersionManager());

        installer.add(plan);
        List<PluginArtifact> nowActive = new ArrayList<>(active);
        nowActive.addAll(plan);
        active = List.copyOf(nowActive);

        for (PluginArtifact artifact : plan) {
            manager.loadPlugin(cache.path(artifact));
        }
        for (PluginArtifact artifact : plan) {
            if (manager.getPlugin(artifact.id()) != null) {
                manager.startPlugin(artifact.id());
            }
        }
        List<PluginInfo> all = plugins();
        return plan.stream()
                .map(a -> all.stream().filter(i -> i.id().equals(a.id())).findFirst().orElseThrow())
                .toList();
    }

    /**
     * Live, read-only view of every active implementation of {@code role}. The same set keeps reflecting
     * plugins as they start and stop; iterate it, do not copy it.
     *
     * @throws IllegalArgumentException if {@code role} is not a {@code @RoleInterface}
     */
    public <T> Set<T> roles(Class<T> role) {
        requireRole(role);
        return registry.view(role);
    }

    /**
     * Creates an application object, injecting its {@code jakarta.inject.Inject} dependencies: exposed host
     * services, {@code Set<SomeRole>} (live) and single roles. The application never touches the injector.
     */
    public <T> T create(Class<T> type) {
        checkOpen();
        return scopes.create(type);
    }

    /**
     * Every installed plugin, in load order, followed by those rejected while resolving (missing dependency,
     * or a dependency version other than the one they were compiled against), as {@code FAILED} with the
     * reason.
     */
    public List<PluginInfo> plugins() {
        List<PluginInfo> result = new ArrayList<>();
        for (PluginWrapper plugin : manager.getPlugins()) {
            result.add(new PluginInfo(plugin.getPluginId(), plugin.getDescriptor().getVersion(),
                    status(plugin), Optional.ofNullable(plugin.getFailedException())));
        }
        for (CrystalPluginManager.Rejection rejection : manager.rejections()) {
            result.add(new PluginInfo(rejection.pluginId(), rejection.version(), PluginInfo.Status.FAILED,
                    Optional.of(rejection.failure())));
        }
        return result;
    }

    /** Stops every plugin (dependents first) and releases their classloaders and injectors. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        manager.stopPlugins();
        manager.unloadPlugins();
        if (ownsCache) {
            deleteRecursively(cache.root());
        }
    }

    private static PluginInfo.Status status(PluginWrapper plugin) {
        return switch (plugin.getPluginState()) {
            case STARTED -> PluginInfo.Status.STARTED;
            case STOPPED -> PluginInfo.Status.STOPPED;
            case FAILED -> PluginInfo.Status.FAILED;
            case DISABLED -> PluginInfo.Status.DISABLED;
            default -> PluginInfo.Status.RESOLVED;
        };
    }

    private static void requireRole(Class<?> role) {
        if (!Roles.isRole(role)) {
            throw new IllegalArgumentException(role.getName() + " is not an interface annotated with @RoleInterface");
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("PluginService is closed");
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Cannot delete {}", p, e);
                }
            });
        } catch (IOException e) {
            log.debug("Cannot clean {}", root, e);
        }
    }

    /** Configures a {@link PluginService}. Everything is optional. */
    public static final class Builder {
        private PluginSource source;
        private Path cacheDirectory;
        private final Map<Class<?>, Object> exposed = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Where plugin jars come from. Optional: without a source the service runs from whatever the cache
         * already holds.
         */
        public Builder source(PluginSource source) {
            this.source = Objects.requireNonNull(source);
            return this;
        }

        /**
         * Persistent local cache the framework owns and loads jars from; created if absent. Pick a directory
         * of the application's own (it may be shared by several instances of the same application).
         * Defaults to a temporary directory deleted on {@link PluginService#close()}, so every start is a
         * first start.
         */
        public Builder cacheDirectory(Path cacheDirectory) {
            this.cacheDirectory = Objects.requireNonNull(cacheDirectory);
            return this;
        }

        /** Makes a host service injectable ({@code @Inject T}) in plugins and in {@code create()}d objects. */
        public <T> Builder expose(Class<T> type, T instance) {
            exposed.put(Objects.requireNonNull(type), Objects.requireNonNull(instance));
            return this;
        }

        public PluginService build() {
            return new PluginService(this);
        }
    }
}
