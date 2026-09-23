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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.crystal.plugins.api.ConflictResolver;
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
 * per plugin, dependency ordering, unload) and Guice wires them (one injector per plugin, dropped with its
 * classloader). Neither library appears in this class's API.
 */
public final class PluginService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);

    private final PluginCache cache;
    private final boolean ownsCache;
    private final PluginInstaller installer;
    private final CrystalPluginManager manager;
    private final RoleRegistry registry;
    private final PluginScopes scopes;
    private List<PluginArtifact> active = List.of();
    private boolean started;
    private boolean closed;

    private PluginService(Builder builder) {
        this.registry = new RoleRegistry(builder.conflictResolver);
        this.ownsCache = builder.cacheDirectory == null;
        try {
            this.cache = new PluginCache(ownsCache
                    ? Files.createTempDirectory("crystal-plugins")
                    : builder.cacheDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create a temporary plugin cache", e);
        }
        this.installer = new PluginInstaller(cache, builder.source, builder.defaults);

        this.manager = new CrystalPluginManager(cache.root());
        this.scopes = new PluginScopes(Map.copyOf(builder.exposed), registry, manager::getExtensionClassNames);
        manager.bind(scopes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads and starts every installed plugin, from the local cache.
     *
     * <p>Starting never installs anything: the {@link PluginSource} is a catalog, and what is installed changes
     * only through {@link #install}, {@link #uninstall}, {@link #checkForUpdates()} and {@link #installAll()}. So a
     * new cache starts with no plugins, and a start never uses the network; the one exception is restoring an
     * installed jar missing from the cache (the same version, never an upgrade).
     *
     * <p>A plugin that fails (unresolvable dependency, exception in {@code onStart}) is reported by
     * {@link #plugins()}; it does not prevent the others from starting.
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
     * Updates what is installed to what the {@link PluginSource} offers now: an installed plugin offered in
     * another version is updated (with any dependency the new version newly needs). Nothing else is installed
     * or removed; the report lists what else is {@code available} (install it with {@link #install}) and what is
     * installed but no longer offered.
     *
     * <p>Each jar is downloaded once and verified by SHA-256. All-or-nothing: if anything fails, the installed set
     * is left as it was. The updates take effect the next time a {@code PluginService} starts on this cache;
     * plugins already running are not touched.
     *
     * @throws IllegalStateException if no source is configured
     * @throws PluginException       if the source cannot be read or serves something other than it advertised
     */
    public synchronized UpdateReport checkForUpdates() {
        checkOpen();
        return installer.updateInstalled(inUse());
    }

    /**
     * Makes the installed set exactly what the {@link PluginSource} offers: adds, updates and removes. For
     * drop-in folders and managed deployments, where the source <em>is</em> the list of plugins to have; called
     * before {@link #start()}, the start loads them. Same guarantees and timing as {@link #checkForUpdates()}.
     */
    public synchronized UpdateReport installAll() {
        checkOpen();
        return installer.installAll(inUse());
    }

    /**
     * What the {@link PluginSource} offers that is not installed, sorted by id: the "can be installed" list of a
     * plugin window. Consults the source (it may use the network) and changes nothing; {@link #install} adds one.
     *
     * @throws IllegalStateException if no source is configured
     * @throws PluginException       if the source cannot be read
     */
    public List<PluginArtifact> available() {
        checkOpen();
        return installer.available();
    }

    /**
     * Where an artifact from {@link #available()} comes from, as its source words it ("plugins folder", "GitHub
     * release"...); see {@link PluginSource#origin}.
     */
    public String origin(PluginArtifact artifact) {
        return installer.origin(artifact);
    }

    private Set<String> inUse() {
        return active.stream().map(PluginArtifact::sha256).collect(Collectors.toSet());
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
            // Already running; if its removal was pending, this cancels it.
            active.stream().filter(a -> a.id().equals(pluginId)).findFirst()
                    .filter(a -> !installer.installedIds().contains(pluginId))
                    .ifPresent(a -> installer.add(List.of(a)));
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
     * Removes {@code pluginId} and every plugin that depends on it: stops and unloads them, sub-plugins first,
     * dropping each one's classloader and injector together, and takes them out of the installed set. Their
     * implementations leave every {@link #roles} view at once; implementations they replaced come back.
     *
     * <p>Only a clean plugin is unloaded: if something outside the plugins being removed holds a fixed reference
     * to one of their implementations (a single {@code @Inject Role} in another plugin, or in a live object of the
     * application, e.g. an injector a Guice-module role was installed in), nothing is done. {@link #heldBy} tells
     * beforehand; {@link #uninstallOnNextStart} removes them when nothing can hold them any more.
     * {@code Set<Role>} views and {@code Provider<Role>} never pin a plugin.
     *
     * @return the removed plugins, sub-plugins first, as {@code UNLOADED}
     * @throws IllegalArgumentException  if {@code pluginId} is not loaded
     * @throws PluginRetainedException  if a removed plugin is still held from outside
     */
    public synchronized List<PluginInfo> uninstall(String pluginId) {
        checkOpen();
        List<String> leavesFirst = withDependents(pluginId);
        List<String> holders = registry.holdersOutside(Set.copyOf(leavesFirst));
        if (!holders.isEmpty()) {
            throw new PluginRetainedException(leavesFirst, holders);
        }
        List<PluginInfo> removed = leavesFirst.stream()
                .map(id -> new PluginInfo(id, manager.getPlugin(id).getDescriptor().getVersion(),
                        PluginInfo.Status.UNLOADED, Optional.empty()))
                .toList();

        installer.remove(Set.copyOf(leavesFirst));
        active = active.stream().filter(a -> !leavesFirst.contains(a.id())).toList();
        manager.unloadInOrder(leavesFirst);
        return removed;
    }

    /**
     * Who would prevent {@link #uninstall}{@code (pluginId)} right now: {@code plugin 'id'} for another plugin, the
     * class name for an application object, holding a fixed reference to an implementation of {@code pluginId} or of
     * a plugin depending on it. Empty when it can be uninstalled now; no need to try and catch.
     *
     * @throws IllegalArgumentException if {@code pluginId} is not loaded
     */
    public List<String> heldBy(String pluginId) {
        return registry.holdersOutside(Set.copyOf(withDependents(pluginId)));
    }

    /**
     * Takes {@code pluginId}, and the plugins depending on it, out of the installed set without unloading them:
     * they keep running until this service closes, and the next start does not load them. For plugins
     * {@link #heldBy} something that lives as long as the application. {@link #install} of the same plugin before
     * then cancels its removal.
     *
     * @return the plugins that will be gone on the next start, sub-plugins first
     */
    public synchronized List<String> uninstallOnNextStart(String pluginId) {
        checkOpen();
        List<String> leavesFirst = manager.getPlugin(pluginId) == null ? List.of(pluginId) : withDependents(pluginId);
        installer.remove(Set.copyOf(leavesFirst));
        return leavesFirst;
    }

    /** Plugins still running but no longer installed ({@link #uninstallOnNextStart}): gone on the next start. */
    public Set<String> pendingRemovals() {
        Set<String> installed = installer.installedIds();
        return manager.getPlugins().stream().map(PluginWrapper::getPluginId)
                .filter(id -> !installed.contains(id)).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private List<String> withDependents(String pluginId) {
        if (manager.getPlugin(pluginId) == null) {
            throw new IllegalArgumentException("Plugin '" + pluginId + "' is not loaded");
        }
        return manager.withDependents(pluginId);
    }

    /**
     * Live, read-only view of the active implementations of {@code role} that the {@link ConflictResolver}
     * lets through, most preferred first (by default: minus the ones replaced with {@code @Replaces}, highest
     * version first). The same set keeps reflecting plugins as they start and stop, and replacements as they
     * come and go; iterate it, do not copy it.
     *
     * @throws IllegalArgumentException if {@code role} is not a {@code @RoleInterface}
     */
    public <T> Set<T> roles(Class<T> role) {
        requireRole(role);
        return registry.view(role);
    }

    /**
     * Creates an application object, injecting its {@code jakarta.inject.Inject} dependencies: exposed host
     * services, {@code Set<SomeRole>} (live), a single {@code SomeRole} (the preferred implementation when the
     * object is created) and {@code Provider<SomeRole>} (the preferred one on every {@code get()}). The
     * application never touches the injector.
     */
    public <T> T create(Class<T> type) {
        checkOpen();
        return scopes.create(type);
    }

    /**
     * The preferred implementation of {@code role} right now: what a single {@code @Inject Role} receives (by
     * default, the one of highest version, after {@code @Replaces}). Meant for dependency-injection adapters;
     * application code injects instead. Whoever keeps the result holds a fixed reference: see
     * {@link #building} and {@link #uninstall}.
     *
     * @throws IllegalArgumentException if {@code role} is not a {@code @RoleInterface}
     * @throws java.util.NoSuchElementException if no active plugin provides it
     */
    public <T> T preferred(Class<T> role) {
        requireRole(role);
        return scopes.preferred(role);
    }

    /**
     * The visible implementations of {@code role} now, as a fixed list rather than a live view: for what has to
     * be consumed before any injection exists, typically roles that configure the application's own container
     * while it is being built. Called inside {@link #building}, the implementations are recorded as held by what
     * is built, so {@link #uninstall} refuses to pull them from under it:
     *
     * <pre>{@code
     * Injector injector = plugins.building(() ->
     *         Guice.createInjector(new AppModule(plugins.snapshot(Extension.class)), PluginsModule.of(plugins)));
     * }</pre>
     */
    public <T> List<T> snapshot(Class<T> role) {
        requireRole(role);
        return scopes.snapshot(role);
    }

    /**
     * Runs {@code build}, typically a dependency-injection container constructing one object, and records every
     * implementation {@link #preferred} or {@link #snapshot} hands out meanwhile as held by the object
     * {@code build} returns, until that object is garbage collected. This is how {@link #uninstall} knows that an
     * application object still holds a plugin; {@link #create} and the adapters work this way. Calls nest: an
     * object built inside another is the holder of what it received itself.
     */
    public <T> T building(Supplier<T> build) {
        checkOpen();
        return scopes.building(build);
    }

    /**
     * Every installed plugin, in load order, followed by those rejected while resolving (missing dependency,
     * or a dependency version other than the one they were compiled against), as {@code FAILED} with the
     * reason.
     */
    public List<PluginInfo> plugins() {
        List<PluginInfo> result = new ArrayList<>();
        for (PluginWrapper plugin : manager.getPlugins()) {
            result.add(describe(plugin));
        }
        for (CrystalPluginManager.Rejection rejection : manager.rejections()) {
            result.add(new PluginInfo(rejection.pluginId(), rejection.version(), PluginInfo.Status.FAILED,
                    Optional.of(rejection.failure())));
        }
        return result;
    }

    /**
     * Every role that has an active implementation, sorted by name: who defines it (a plugin, or the application)
     * and its implementations, each with the plugin it comes from and whether it is visible or hidden by the
     * {@link ConflictResolver}. Together with {@link #plugins()}, the model of a plugin panel.
     */
    public List<RoleInfo> roleTree() {
        Map<Class<?>, List<RoleInfo.Implementation>> byRole = new LinkedHashMap<>();
        for (RoleRegistry.Entry e : registry.entries()) {
            byRole.computeIfAbsent(e.role(), r -> new ArrayList<>()).add(
                    new RoleInfo.Implementation(e.pluginId(), e.instance().getClass().getName(), e.visible()));
        }
        List<RoleInfo> tree = new ArrayList<>();
        byRole.forEach((role, implementations) -> tree.add(new RoleInfo(role.getName(), definer(role),
                implementations)));
        tree.sort(Comparator.comparing(RoleInfo::role));
        return tree;
    }

    private Optional<String> definer(Class<?> role) {
        return manager.getPlugins().stream().filter(p -> p.getPluginClassLoader() == role.getClassLoader())
                .map(PluginWrapper::getPluginId).findFirst();
    }

    private PluginInfo describe(PluginWrapper plugin) {
        List<PluginInfo.ExtensionInfo> extensions = new ArrayList<>();
        for (String className : new java.util.TreeSet<>(manager.getExtensionClassNames(plugin.getPluginId()))) {
            try {
                Class<?> type = Class.forName(className, false, plugin.getPluginClassLoader());
                List<String> roles = Roles.of(type).stream().map(Class::getName).toList();
                dev.crystal.plugins.api.Replaces replaces = type.getAnnotation(dev.crystal.plugins.api.Replaces.class);
                if (!roles.isEmpty()) {
                    extensions.add(new PluginInfo.ExtensionInfo(className, roles,
                            replaces == null ? List.of() : List.of(replaces.value())));
                }
            } catch (ClassNotFoundException | LinkageError e) {
                log.debug("Cannot describe extension {} of {}", className, plugin.getPluginId(), e);
            }
        }
        List<String> dependencies = plugin.getDescriptor().getDependencies().stream()
                .map(d -> d.getPluginVersionSupport() == null || "*".equals(d.getPluginVersionSupport())
                        ? d.getPluginId() + (d.isOptional() ? "?" : "")
                        : d.getPluginId() + "@" + d.getPluginVersionSupport())
                .toList();
        List<String> defines = new ArrayList<>();
        Optional<String> apiVersion = Optional.empty();
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(plugin.getPluginPath().toFile())) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest != null) {
                apiVersion = Optional.ofNullable(manifest.getMainAttributes().getValue("Crystal-Api-Version"));
            }
            java.util.zip.ZipEntry index = jar.getEntry("META-INF/crystal/roles.idx");
            if (index != null) {
                try (java.io.InputStream in = jar.getInputStream(index)) {
                    for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .split("\\R")) {
                        String entry = line.replaceFirst("#.*", "").strip();
                        if (!entry.isEmpty()) {
                            defines.add(entry);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Cannot read {}", plugin.getPluginPath(), e);
        }
        return new PluginInfo(plugin.getPluginId(), plugin.getDescriptor().getVersion(), status(plugin),
                Optional.ofNullable(plugin.getFailedException()), extensions, defines, dependencies, apiVersion);
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
        private PluginSource defaults;
        private Path cacheDirectory;
        private ConflictResolver conflictResolver = ConflictResolver.standard();
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
         * Plugins shipped with the application (typically {@link PluginSources#bundled()}): installed on the first
         * start of the cache, extracted and verified into it, without any network. After that they are ordinary
         * installed plugins: an uninstalled one does not come back, {@link PluginService#install} can take any of
         * them again, and {@link PluginService#checkForUpdates()} considers them when the main source does not
         * offer their id.
         */
        public Builder defaults(PluginSource defaults) {
            this.defaults = Objects.requireNonNull(defaults);
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

        /**
         * How to choose among several implementations of a role. Defaults to
         * {@link ConflictResolver#standard()}: {@code @Replaces} wins, then the highest version.
         */
        public Builder conflictResolver(ConflictResolver conflictResolver) {
            this.conflictResolver = Objects.requireNonNull(conflictResolver);
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
