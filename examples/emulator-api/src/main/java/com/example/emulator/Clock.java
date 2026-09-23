package com.example.emulator;

/** Host service (not a role): the emulator exposes it, peripherals inject it. */
public interface Clock {
    long tStates();
}
