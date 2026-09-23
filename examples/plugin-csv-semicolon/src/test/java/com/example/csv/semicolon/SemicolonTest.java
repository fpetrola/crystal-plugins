package com.example.csv.semicolon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.reports.ReportExporter;

import dev.crystal.plugins.harness.PluginHarness;

/** A sub-plugin tested with its parent, which the harness finds on the test class path. */
class SemicolonTest {

    @Test
    void theCsvExporterUsesThisDialect() {
        try (PluginHarness harness = PluginHarness.start()) {
            ReportExporter csv = harness.one(ReportExporter.class);

            assertEquals("a;b\n1;2", csv.export(List.of("a", "b"), List.of(List.of("1", "2"))));
        }
    }
}
