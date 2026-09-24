package dev.crystal.plugins.runtime.fixtures;

import java.util.List;

import dev.crystal.plugins.api.HasLifecycle;
import jakarta.inject.Inject;

/** An implementation the application itself brings (declared in META-INF/services by the tests that use it). */
public class HostExporter implements ReportExporter, HasLifecycle {

    @Inject
    Journal journal;

    @Override
    public String format() {
        return "host";
    }

    @Override
    public String export(List<String> rows) {
        return "";
    }

    @Override
    public void onStart() {
        journal.log("host start");
    }

    @Override
    public void onStop() {
        journal.log("host stop");
    }
}
