package dev.crystal.plugins.runtime.fixtures;

/** A host service exposed to plugins. */
public final class Bus {
    public int clock() {
        return 3_500_000;
    }
}
