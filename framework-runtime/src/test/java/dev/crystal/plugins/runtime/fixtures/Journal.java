package dev.crystal.plugins.runtime.fixtures;

import java.util.ArrayList;
import java.util.List;

/** Host service plugins write to, so tests can observe lifecycle calls. */
public final class Journal {
    private final List<String> entries = new ArrayList<>();

    public synchronized void log(String entry) {
        entries.add(entry);
    }

    public synchronized List<String> entries() {
        return List.copyOf(entries);
    }
}
