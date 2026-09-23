package dev.crystal.plugins.build.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PluginVersionsTest {

    @Test
    void mapsMavenVersionsToSemVer() {
        assertEquals("1.0.0", PluginVersions.toSemVer("1"));
        assertEquals("1.2.0", PluginVersions.toSemVer("1.2"));
        assertEquals("1.2.3", PluginVersions.toSemVer("1.2.3"));
        assertEquals("1.2.3-SNAPSHOT", PluginVersions.toSemVer("1.2.3-SNAPSHOT"));
        assertEquals("1.2.0-SNAPSHOT", PluginVersions.toSemVer("1.2-SNAPSHOT"));
        assertEquals("1.2.3-Final", PluginVersions.toSemVer("1.2.3.Final"));
    }

    @Test
    void rejectsNonNumericVersions() {
        assertThrows(IllegalArgumentException.class, () -> PluginVersions.toSemVer("latest"));
    }
}
