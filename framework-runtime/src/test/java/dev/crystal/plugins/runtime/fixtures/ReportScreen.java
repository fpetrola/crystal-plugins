package dev.crystal.plugins.runtime.fixtures;

import java.util.Set;

import jakarta.inject.Inject;

/** An application object: consumes plugins by injection, knows nothing about PF4J or Guice. */
public final class ReportScreen {
    private final Set<ReportExporter> exporters;

    @Inject
    public ReportScreen(Set<ReportExporter> exporters) {
        this.exporters = exporters;
    }

    public Set<ReportExporter> exporters() {
        return exporters;
    }
}
