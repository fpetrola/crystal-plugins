package dev.crystal.plugins.runtime.fixtures;

import dev.crystal.plugins.api.RoleInterface;

/** Emulator host role: a device on the bus. */
@RoleInterface
public interface Peripheral {
    String name();

    int read(int port);
}
