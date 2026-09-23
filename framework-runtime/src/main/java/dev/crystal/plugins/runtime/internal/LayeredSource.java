package dev.crystal.plugins.runtime.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginSource;

/**
 * The catalog the installer sees: the main source first, then the application's default plugins for ids the
 * main source does not offer. Each artifact is opened from the source that offered it.
 */
final class LayeredSource implements PluginSource {

    private final PluginSource main;
    private final PluginSource defaults;
    private final Map<PluginArtifact, PluginSource> origin = new IdentityHashMap<>();

    LayeredSource(PluginSource main, PluginSource defaults) {
        this.main = main;
        this.defaults = defaults;
    }

    @Override
    public synchronized List<PluginArtifact> artifacts() throws IOException {
        origin.clear();
        List<PluginArtifact> offered = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (PluginArtifact a : main.artifacts()) {
            offered.add(a);
            ids.add(a.id());
            origin.put(a, main);
        }
        for (PluginArtifact a : defaults.artifacts()) {
            if (ids.add(a.id())) {
                offered.add(a);
                origin.put(a, defaults);
            }
        }
        return offered;
    }

    @Override
    public synchronized InputStream open(PluginArtifact artifact) throws IOException {
        for (Map.Entry<PluginArtifact, PluginSource> e : origin.entrySet()) {
            if (e.getKey().equals(artifact)) {
                return e.getValue().open(e.getKey());
            }
        }
        throw new IOException("Unknown artifact " + artifact.coordinates());
    }

    @Override
    public synchronized String origin(PluginArtifact artifact) {
        for (Map.Entry<PluginArtifact, PluginSource> e : origin.entrySet()) {
            if (e.getKey().equals(artifact)) {
                return e.getValue().origin(e.getKey());
            }
        }
        return toString();
    }

    @Override
    public String toString() {
        return main + " + " + defaults;
    }
}
