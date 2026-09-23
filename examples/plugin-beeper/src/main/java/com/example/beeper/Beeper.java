package com.example.beeper;

import com.example.emulator.Clock;
import com.example.emulator.Peripheral;

import dev.crystal.plugins.api.HasLifecycle;
import jakarta.inject.Inject;

/** The whole plugin: one class implementing a role. */
public class Beeper implements Peripheral, HasLifecycle {

    private final Clock clock;
    private long lastToggle = -1;
    private boolean on;

    @Inject
    public Beeper(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "beeper";
    }

    @Override
    public boolean handles(int port) {
        return (port & 0xFF) == 0xFE;
    }

    @Override
    public int in(int port) {
        return on ? 0xFF : 0xBF;
    }

    @Override
    public void out(int port, int value) {
        on = (value & 0x10) != 0;
        lastToggle = clock.tStates();
    }

    @Override
    public void onStart() {
        lastToggle = clock.tStates();
    }

    public long lastToggle() {
        return lastToggle;
    }
}
