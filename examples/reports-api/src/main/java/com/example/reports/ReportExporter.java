package com.example.reports;

import java.util.List;

import dev.crystal.plugins.api.RoleInterface;

/** An output format for tabular reports. */
@RoleInterface
public interface ReportExporter {

    String format();

    String export(List<String> header, List<List<String>> rows);
}
