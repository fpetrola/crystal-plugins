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
 * <p>A source is consulted <strong>only</strong> when the cache cannot answer: the first start on an empty
 * cache, an explicit {@code checkForUpdates()}, and the repair of a damaged cache. Starting with every
 * installed plugin already cached never calls the source, so implementations are free to use the network.
 */
public interface PluginSource {

    /**
     * The artifacts this source currently offers, at most one per plugin id. The runtime installs exactly
     * this set: choosing which version to offer is the source's decision, never the framework's.
     */
    List<PluginArtifact> artifacts() throws IOException;

    /**
     * Opens the bytes of an artifact returned by the latest {@link #artifacts()} call. Only called for
     * artifacts that are not cached yet. The caller closes the stream.
     */
    InputStream open(PluginArtifact artifact) throws IOException;
}
