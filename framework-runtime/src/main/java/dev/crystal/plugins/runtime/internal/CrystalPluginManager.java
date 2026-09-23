package dev.crystal.plugins.runtime.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.DefaultPluginFactory;
import org.pf4j.DefaultPluginManager;
import org.pf4j.DefaultVersionManager;
import org.pf4j.DependencyResolver;
import org.pf4j.JarPluginLoader;
import org.pf4j.Plugin;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginFactory;
import org.pf4j.PluginLoader;
import org.pf4j.PluginRepository;
import org.pf4j.PluginStatusProvider;
import org.pf4j.PluginWrapper;
import org.pf4j.RuntimeMode;
import org.pf4j.VersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.crystal.plugins.runtime.PluginException;

/**
 * PF4J, narrowed to what the framework needs. Everything else (classloaders, dependency resolution and
 * ordering, extension index, start/stop/unload) is stock PF4J.
 *
 * <ul>
 *   <li>The repository is the local cache: it returns exactly the jars the installer selected for this run
 *       (the installed set), instead of scanning a directory. Loading is stock {@link JarPluginLoader}:
 *       every plugin is read from local disk.</li>
 *   <li>Always deployment mode, jar plugins only.</li>
 *   <li>No {@code enabled.txt}/{@code disabled.txt}: enablement is in memory (zero config files).</li>
 *   <li>A plugin whose dependencies are not satisfied is rejected (with the reason kept), instead of
 *       aborting the whole load, and without taking its dependencies down with it.</li>
 *   <li>An exact version pin ({@code id@1.2.3}, what the build writes) also works for pre-releases.</li>
 *   <li>{@link RolePlugin} is created for jars whose {@code Plugin-Class} is the generic class or absent;
 *       a classic PF4J plugin with its own {@code Plugin-Class} still works.</li>
 * </ul>
 */
public final class CrystalPluginManager extends DefaultPluginManager {

    private static final Logger log = LoggerFactory.getLogger(CrystalPluginManager.class);

    /** A plugin that was loaded but could not be resolved, and why. */
    public record Rejection(String pluginId, String version, PluginException failure) {

        public String reason() {
            return failure.getMessage();
        }
    }

    /** {@code 1.2.3}, {@code =1.2.3-SNAPSHOT}: an exact version, as opposed to a range expression. */
    private static final Pattern EXACT = Pattern.compile("=?(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)(?:\\+[0-9A-Za-z.-]+)?");

    private final Map<String, Rejection> rejections = Collections.synchronizedMap(new LinkedHashMap<>());

    // Both set after construction: PF4J calls the create* factories from its own constructor, so the
    // repository and factory below read these fields lazily.
    private PluginScopes scopes;
    private volatile List<Path> pluginPaths = List.of();

    public CrystalPluginManager(Path pluginsRoot) {
        super(pluginsRoot);
    }

    /**
     * {@code pluginId} and every plugin that depends on it, transitively, leaves first: the order in which they
     * can be stopped and unloaded without any of them outliving what it depends on.
     */
    public List<String> withDependents(String pluginId) {
        Set<String> order = new java.util.LinkedHashSet<>();
        collectDependents(pluginId, order);
        return List.copyOf(order);
    }

    private void collectDependents(String pluginId, Set<String> order) {
        for (String dependent : dependencyResolver.getDependents(pluginId)) {
            collectDependents(dependent, order);
        }
        order.add(pluginId);
    }

    /**
     * Stops and unloads {@code leavesFirst} in that order. PF4J's own transitive unload handles a dependent
     * before the dependents of that dependent, so in A &lt;- B &lt;- C it unloads B while C still runs on it.
     */
    public void unloadInOrder(List<String> leavesFirst) {
        for (String pluginId : leavesFirst) {
            unloadPlugin(pluginId, false, false);
        }
        resolveDependencies();
    }

    /** Plugins rejected while resolving, in rejection order. */
    public List<Rejection> rejections() {
        synchronized (rejections) {
            return new ArrayList<>(rejections.values());
        }
    }

    public void bind(PluginScopes scopes) {
        this.scopes = scopes;
    }

    /** The jars {@link #loadPlugins()} will load: the installed set, resolved to cache paths. */
    public void usePlugins(List<Path> paths) {
        this.pluginPaths = List.copyOf(paths);
    }

    @Override
    public RuntimeMode getRuntimeMode() {
        return RuntimeMode.DEPLOYMENT;
    }

    @Override
    protected PluginRepository createPluginRepository() {
        return new PluginRepository() {
            @Override
            public List<Path> getPluginPaths() {
                return pluginPaths;
            }

            @Override
            public boolean deletePluginPath(Path pluginPath) {
                // Cached jars are immutable and may be shared; the set changes through the installer only.
                return false;
            }
        };
    }

    @Override
    protected PluginLoader createPluginLoader() {
        return new JarPluginLoader(this);
    }

    @Override
    protected PluginStatusProvider createPluginStatusProvider() {
        Set<String> disabled = new HashSet<>();
        return new PluginStatusProvider() {
            @Override
            public synchronized boolean isPluginDisabled(String pluginId) {
                return disabled.contains(pluginId);
            }

            @Override
            public synchronized void disablePlugin(String pluginId) {
                disabled.add(pluginId);
            }

            @Override
            public synchronized void enablePlugin(String pluginId) {
                disabled.remove(pluginId);
            }
        };
    }

    /**
     * PF4J's recovery ({@code IGNORE_PLUGIN_AND_CONTINUE}) unloads the <em>dependency</em> of a requirement whose
     * version does not match, so one plugin with a stale pin takes a healthy plugin and all its other dependents
     * down with it, silently. Here the plugin whose requirement is not met is the one rejected, transitively,
     * and the reason is kept for {@link #rejections()}. Resolution itself (graph, order, checks) is PF4J's.
     */
    @Override
    protected DependencyResolver.Result resolveDependencies() {
        while (true) {
            List<PluginDescriptor> descriptors = plugins.values().stream().map(PluginWrapper::getDescriptor).toList();
            DependencyResolver.Result result = dependencyResolver.resolve(descriptors);
            if (result.isOK()) {
                return result;
            }
            if (result.hasCyclicDependency()) {
                throw new DependencyResolver.CyclicDependencyException();
            }
            Map<String, String> reasons = new LinkedHashMap<>();
            for (String missing : result.getNotFoundDependencies()) {
                String why = rejections.containsKey(missing)
                        ? "requires plugin '" + missing + "', which was rejected: " + rejections.get(missing).reason()
                        : "requires plugin '" + missing + "', which is not installed";
                for (String dependent : dependencyResolver.getDependents(missing)) {
                    reasons.putIfAbsent(dependent, why);
                }
            }
            for (DependencyResolver.WrongDependencyVersion wrong : result.getWrongVersionDependencies()) {
                reasons.putIfAbsent(wrong.getDependentId(), "requires " + wrong.getDependencyId() + "@"
                        + wrong.getRequiredVersion() + " but " + wrong.getExistingVersion() + " is installed");
            }
            boolean removed = false;
            for (Map.Entry<String, String> reason : reasons.entrySet()) {
                PluginWrapper plugin = plugins.get(reason.getKey());
                if (plugin == null) {
                    continue;
                }
                log.error("Plugin '{}' rejected: {}", getPluginLabel(plugin.getDescriptor()), reason.getValue());
                rejections.put(plugin.getPluginId(), new Rejection(plugin.getPluginId(),
                        plugin.getDescriptor().getVersion(), new PluginException(reason.getValue())));
                unloadPlugin(plugin.getPluginId(), false, false);
                removed = true;
            }
            if (!removed) {
                return result; // nothing left to reject; PF4J would loop the same way
            }
        }
    }

    /**
     * PF4J evaluates versions with java-semver expressions, which cannot express a pre-release
     * ({@code 1.0.0-SNAPSHOT} fails to parse). An exact pin is compared here, ignoring build metadata as
     * SemVer precedence does; ranges and wildcards are left to PF4J.
     */
    @Override
    protected VersionManager createVersionManager() {
        return new DefaultVersionManager() {
            @Override
            public boolean checkVersionConstraint(String version, String constraint) {
                Matcher exact = EXACT.matcher(constraint == null ? "" : constraint.trim());
                if (exact.matches()) {
                    return withoutBuildMetadata(version).equals(exact.group(1));
                }
                return super.checkVersionConstraint(version, constraint);
            }
        };
    }

    private static String withoutBuildMetadata(String version) {
        int plus = version.indexOf('+');
        return plus < 0 ? version : version.substring(0, plus);
    }

    @Override
    protected PluginFactory createPluginFactory() {
        DefaultPluginFactory classic = new DefaultPluginFactory();
        return new PluginFactory() {
            @Override
            public Plugin create(PluginWrapper wrapper) {
                String pluginClass = wrapper.getDescriptor().getPluginClass();
                // PF4J substitutes org.pf4j.Plugin when the manifest has no Plugin-Class.
                if (pluginClass == null || pluginClass.isBlank() || pluginClass.equals(Plugin.class.getName())
                        || pluginClass.equals(RolePlugin.class.getName())) {
                    return new RolePlugin(wrapper, scopes);
                }
                return classic.create(wrapper);
            }
        };
    }
}
