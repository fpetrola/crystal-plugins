package dev.crystal.plugins.runtime.fixtures;

import java.util.List;

import dev.crystal.plugins.api.RoleInterface;

/** Reporting app role: an output format. */
@RoleInterface
public interface ReportExporter {
    String format();

    String export(List<String> rows);
}
