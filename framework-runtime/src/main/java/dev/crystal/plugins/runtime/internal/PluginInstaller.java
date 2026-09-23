package dev.crystal.plugins.runtime.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginDependency;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginRuntimeException;
import org.pf4j.VersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;
import dev.crystal.plugins.runtime.PluginException;
import dev.crystal.plugins.runtime.UpdateReport;

/**
 * Decides when the {@link PluginSource} is consulted, and keeps the cache consistent when it is.
 *
 * <ul>
 *   <li>{@link #prepare()} (every start): the installed set comes from disk. The source is used only if
 *       nothing was ever installed (first run) or a cached jar went missing (repair, same versions).</li>
 *   <li>{@link #update(Set)} (explicit): installs exactly what the source offers now. All-or-nothing: every
 *       jar is downloaded and verified before the new installed set is written, so a failed or interrupted
 *       update leaves the previous one untouched.</li>
 *   <li>{@link #plan} + {@link #add} (explicit): adds one plugin and the dependencies it lacks to what is
 *       running, without touching anything else.</li>
 * </ul>
 */
public final class PluginInstaller {

    private static final Logger log = LoggerFactory.getLogger(PluginInstaller.class);

    private final PluginCache cache;
    private final PluginSource source;

    /** @param source may be null: the service then runs from whatever the cache holds */
    public PluginInstaller(PluginCache cache, PluginSource source) {
        this.cache = cache;
        this.source = source;
    }

    /** The plugins to load now, every one of them present in the cache. */
    public List<PluginArtifact> prepare() {
        Optional<List<PluginArtifact>> installed = cache.installed();
        if (installed.isEmpty()) {
            if (source == null) {
                return List.of();
            }
            log.info("No plugins installed in {} yet; installing from {}", cache.root(), source);
            update(Set.of());
            installed = cache.installed();
        }
        List<PluginArtifact> missing = installed.orElseThrow().stream().filter(a -> !cache.contains(a)).toList();
        if (!missing.isEmpty()) {
            repair(missing);
        }
        return installed.orElseThrow().stream().filter(cache::contains).toList();
    }

    /**
     * Installs the source's current offer.
     *
     * @param inUse hashes of jars loaded by this process; they survive the prune that follows
     */
    public UpdateReport update(Set<String> inUse) {
        if (source == null) {
            throw new IllegalStateException("No PluginSource configured");
        }
        List<PluginArtifact> offer;
        try {
            offer = source.artifacts();
        } catch (IOException e) {
            throw new PluginException("Cannot list plugins of " + source, e);
        }
        Map<String, PluginArtifact> next = byId(offer);

        int downloads = 0;
        for (PluginArtifact artifact : next.values()) {
            try {
                if (cache.fetch(source, artifact)) {
                    downloads++;
                }
            } catch (IOException e) {
                throw new PluginException("Cannot download " + artifact.coordinates() + " from " + source, e);
            }
        }

        Map<String, PluginArtifact> previous = byId(cache.installed().orElse(List.of()));
        try {
            cache.install(List.copyOf(next.values()));
        } catch (IOException e) {
            throw new PluginException("Cannot write the installed set in " + cache.root(), e);
        }

        // Keep the previous generation too: other processes sharing the cache may still be running it.
        Set<String> keep = new HashSet<>(inUse);
        previous.values().forEach(a -> keep.add(a.sha256()));
        next.values().forEach(a -> keep.add(a.sha256()));
        cache.prune(keep);

        UpdateReport report = new UpdateReport(diff(previous, next), downloads);
        log.info("Plugin update from {}: {} change(s), {} download(s)", source, report.changes().size(), downloads);
        return report;
    }

    /**
     * What to add so that {@code pluginId} runs next to the plugins loaded now: the plugin and the
     * dependencies it lacks, dependencies first, every jar already downloaded and verified. Nothing is installed
     * yet and any problem is an exception, so a plan that fails changes nothing.
     *
     * <p>Dependencies come from each jar's {@code Plugin-Dependencies}, read by PF4J's own descriptor finder, and
     * are checked with {@code versions}, the same rules the runtime resolves with. A plugin in the installed set
     * keeps its installed version (no implicit upgrade); one that is loaded is never replaced.
     *
     * @param loaded id → version of every plugin loaded now
     */
    public List<PluginArtifact> plan(String pluginId, Map<String, String> loaded, VersionManager versions) {
        if (source == null) {
            throw new IllegalStateException("No PluginSource configured");
        }
        List<PluginArtifact> offered;
        try {
            offered = source.artifacts();
        } catch (IOException e) {
            throw new PluginException("Cannot list plugins of " + source, e);
        }
        Planner planner = new Planner(byId(offered), byId(cache.installed().orElse(List.of())), loaded, versions);
        planner.visit(pluginId, null, null);
        return List.copyOf(planner.plan);
    }

    /** Adds {@code artifacts} to the installed set (they must be cached already). */
    public void add(List<PluginArtifact> artifacts) {
        Map<String, PluginArtifact> set = byId(cache.installed().orElse(List.of()));
        artifacts.forEach(a -> set.put(a.id(), a));
        try {
            cache.install(List.copyOf(set.values()));
        } catch (IOException e) {
            throw new PluginException("Cannot write the installed set in " + cache.root(), e);
        }
    }

    /** Removes {@code pluginIds} from the installed set; their jars stay cached until the next prune. */
    public void remove(Set<String> pluginIds) {
        Map<String, PluginArtifact> set = byId(cache.installed().orElse(List.of()));
        set.keySet().removeAll(pluginIds);
        try {
            cache.install(List.copyOf(set.values()));
        } catch (IOException e) {
            throw new PluginException("Cannot write the installed set in " + cache.root(), e);
        }
    }

    /** Depth-first over {@code Plugin-Dependencies}; the plan lists dependencies before their dependents. */
    private final class Planner {
        private final Map<String, PluginArtifact> offered;
        private final Map<String, PluginArtifact> installed;
        private final Map<String, String> loaded;
        private final VersionManager versions;
        private final Set<String> visiting = new LinkedHashSet<>();
        private final List<PluginArtifact> plan = new ArrayList<>();

        Planner(Map<String, PluginArtifact> offered, Map<String, PluginArtifact> installed, Map<String, String> loaded,
                VersionManager versions) {
            this.offered = offered;
            this.installed = installed;
            this.loaded = loaded;
            this.versions = versions;
        }

        void visit(String id, String constraint, String requiredBy) {
            String who = requiredBy == null ? "" : "'" + requiredBy + "' requires " + id + "@" + constraint + " but ";
            String running = loaded.get(id);
            if (running != null) {
                if (!satisfies(running, constraint)) {
                    throw new PluginException(who + id + " " + running + " is running, and replacing a running plugin "
                            + "is not supported yet");
                }
                return;
            }
            for (PluginArtifact planned : plan) {
                if (planned.id().equals(id)) {
                    if (!satisfies(planned.version(), constraint)) {
                        throw new PluginException(who + id + " " + planned.version() + " is being installed");
                    }
                    return;
                }
            }
            if (!visiting.add(id)) {
                throw new PluginException("Dependency cycle: " + String.join(" -> ", visiting) + " -> " + id);
            }
            PluginArtifact artifact = installed.containsKey(id) ? installed.get(id) : offered.get(id);
            if (artifact == null) {
                throw new PluginException(requiredBy == null
                        ? "Plugin '" + id + "' is not offered by " + source
                        : "'" + requiredBy + "' requires plugin '" + id + "', which " + source + " does not offer");
            }
            if (!satisfies(artifact.version(), constraint)) {
                throw new PluginException(who + (installed.containsKey(id) ? "the installed" : "the offered")
                        + " version is " + artifact.version());
            }
            try {
                cache.fetch(source, artifact);
            } catch (IOException e) {
                throw new PluginException("Cannot download " + artifact.coordinates() + " from " + source, e);
            }
            for (PluginDependency dependency : descriptor(artifact).getDependencies()) {
                if (!dependency.isOptional()) {
                    visit(dependency.getPluginId(), dependency.getPluginVersionSupport(), id);
                }
            }
            visiting.remove(id);
            plan.add(artifact);
        }

        private boolean satisfies(String version, String constraint) {
            return constraint == null || constraint.isBlank() || "*".equals(constraint.trim())
                    || versions.checkVersionConstraint(version, constraint);
        }

        private PluginDescriptor descriptor(PluginArtifact artifact) {
            try {
                return new ManifestPluginDescriptorFinder().find(cache.path(artifact));
            } catch (PluginRuntimeException e) {
                throw new PluginException("Cannot read the manifest of " + artifact.coordinates(), e);
            }
        }
    }

    /** Re-downloads missing jars of the installed set, only if the source still offers the very same bytes. */
    private void repair(List<PluginArtifact> missing) {
        List<PluginArtifact> offer = List.of();
        if (source != null) {
            try {
                offer = source.artifacts();
            } catch (IOException e) {
                log.error("Plugin cache {} is missing {} and {} is unavailable", cache.root(), coordinates(missing),
                        source, e);
            }
        }
        for (PluginArtifact artifact : missing) {
            if (!offer.contains(artifact)) {
                log.error("Installed plugin {} is missing from the cache and cannot be restored as is; it will not be "
                        + "loaded (checkForUpdates() installs what the source offers now)", artifact.coordinates());
                continue;
            }
            try {
                cache.fetch(source, artifact);
                log.warn("Restored missing plugin {} in cache {}", artifact.coordinates(), cache.root());
            } catch (IOException | PluginException e) {
                log.error("Cannot restore plugin {}", artifact.coordinates(), e);
            }
        }
    }

    private static Map<String, PluginArtifact> byId(List<PluginArtifact> artifacts) {
        Map<String, PluginArtifact> result = new TreeMap<>();
        for (PluginArtifact artifact : artifacts) {
            PluginArtifact other = result.putIfAbsent(artifact.id(), artifact);
            if (other != null && !other.equals(artifact)) {
                throw new PluginException("Plugin '" + artifact.id() + "' is offered twice (" + other.version()
                        + " and " + artifact.version() + "); a source must offer at most one version per plugin");
            }
        }
        return result;
    }

    private static List<UpdateReport.Change> diff(Map<String, PluginArtifact> before, Map<String, PluginArtifact> after) {
        Set<String> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        List<UpdateReport.Change> changes = new ArrayList<>();
        for (String id : ids) {
            PluginArtifact from = before.get(id);
            PluginArtifact to = after.get(id);
            if (from == null) {
                changes.add(new UpdateReport.Change(UpdateReport.Kind.ADDED, id, null, to.version()));
            } else if (to == null) {
                changes.add(new UpdateReport.Change(UpdateReport.Kind.REMOVED, id, from.version(), null));
            } else if (!from.sha256().equals(to.sha256())) {
                changes.add(new UpdateReport.Change(UpdateReport.Kind.UPDATED, id, from.version(), to.version()));
            }
        }
        return changes;
    }

    private static String coordinates(List<PluginArtifact> artifacts) {
        return artifacts.stream().map(PluginArtifact::coordinates).collect(Collectors.joining(", "));
    }
}
