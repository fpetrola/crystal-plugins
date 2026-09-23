package com.example.beeper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.example.emulator.Clock;
import com.example.emulator.Peripheral;

import dev.crystal.plugins.harness.PluginHarness;

/** The plugin tested on its own: its API jar, the harness, and a stand-in for the host's clock. */
class BeeperTest {

    @Test
    void togglesOnPortFe() {
        long[] now = {1000};
        try (PluginHarness harness = PluginHarness.builder().expose(Clock.class, () -> now[0]).start()) {
            Peripheral beeper = harness.one(Peripheral.class);

            assertEquals(0xBF, beeper.in(0xFE));
            now[0] = 2000;
            beeper.out(0xFE, 0x10);
            assertEquals(0xFF, beeper.in(0xFE));
        }
    }
}
