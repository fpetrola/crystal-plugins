package dev.crystal.plugins.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Where plugin jars come from. Implemented by the host application.
 *
 * <p>The framework has no networking code at all: downloading, authentication, mirrors, signatures of a
 * remote catalog... all of that lives behind this interface. The runtime only asks two things: what is
 * available, and the bytes of one artifact. It stores those bytes in its local cache, verified by SHA-256,
 * and from then on loads from disk.
 *
 * <p>A source is a <em>catalog</em>: what can be installed, not what is. What is installed is the runtime's own
 * state and changes only through explicit operations ({@code install}, {@code uninstall},
 * {@code checkForUpdates}, {@code installAll}), which are the only ones that consult the source, besides
 * restoring an installed jar missing from the cache. Starting never calls it, so implementations are free to
 * use the network.
 */
public interface PluginSource {

    /**
     * The artifacts this source currently offers, at most one per plugin id. Choosing which version to offer is
     * the source's decision, never the framework's.
     */
    List<PluginArtifact> artifacts() throws IOException;

    /**
     * Opens the bytes of an artifact returned by the latest {@link #artifacts()} call. Only called for
     * artifacts that are not cached yet. The caller closes the stream.
     */
    InputStream open(PluginArtifact artifact) throws IOException;

    /**
     * Where an artifact returned by {@link #artifacts()} comes from, in words for a person ("plugins folder",
     * "GitHub release"...): a source that merges several places tells which one won. Shown by plugin windows;
     * never part of the artifact's identity. Defaults to this source's {@code toString()}.
     */
    default String origin(PluginArtifact artifact) {
        return toString();
    }

    /**
     * What an artifact returned by {@link #artifacts()} brings (roles, dependencies), for showing it before it is
     * installed. Empty when the source cannot tell without downloading it, which is the default. A remote catalog
     * can publish each plugin's {@code META-INF/plugin-metadata.json} next to the jar and read it here
     * ({@code PluginSources.description} parses it).
     */
    default java.util.Optional<PluginDescription> describe(PluginArtifact artifact) {
        return java.util.Optional.empty();
    }
}
