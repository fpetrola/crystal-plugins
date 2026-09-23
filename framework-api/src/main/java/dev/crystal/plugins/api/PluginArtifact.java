package dev.crystal.plugins.api;

import java.util.Objects;

/**
 * A plugin jar as advertised by a {@link PluginSource}.
 *
 * @param id      plugin id; must match {@code Plugin-Id} in the jar's manifest
 * @param version plugin version; must match {@code Plugin-Version} in the jar's manifest
 * @param sha256  lower-case hex SHA-256 of the jar bytes. The runtime verifies it while downloading and
 *                uses it as the cache key, so a source must never serve different bytes under one hash
 */
public record PluginArtifact(String id, String version, String sha256) {

    public PluginArtifact {
        requireToken(id, "id");
        requireToken(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lower-case hex chars: " + sha256);
        }
    }

    /** {@code id@version}, the form used in logs and messages. */
    public String coordinates() {
        return id + "@" + version;
    }

    private static void requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no whitespace: '" + value + "'");
        }
    }
}
