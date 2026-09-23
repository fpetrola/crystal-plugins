package com.example.emulator;

import dev.crystal.plugins.api.RoleInterface;

/** A device attached to the emulated I/O bus. */
@RoleInterface
public interface Peripheral {

    String name();

    boolean handles(int port);

    int in(int port);

    void out(int port, int value);
}
