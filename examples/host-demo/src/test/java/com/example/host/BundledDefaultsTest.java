package com.example.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.example.emulator.Clock;

import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.PluginSources;

/** The application carries its default plugins inside: first start, no folder, no network. */
class BundledDefaultsTest {

    @Test
    void theFirstStartInstallsThePluginsTheApplicationCarries() {
        try (PluginService plugins = PluginService.builder()
                .defaults(PluginSources.bundled())
                .expose(Clock.class, () -> 0L)
                .build()) {
            plugins.start();

            Set<String> started = plugins.plugins().stream().filter(p -> p.status() == PluginInfo.Status.STARTED)
                    .map(PluginInfo::id).collect(Collectors.toSet());
            assertEquals(Set.of("plugin-beeper", "plugin-csv-exporter", "plugin-csv-semicolon",
                    "plugin-markdown-exporter"), started);
        }
    }
}
