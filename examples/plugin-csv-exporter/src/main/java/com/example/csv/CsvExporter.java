package com.example.csv;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.example.reports.ReportExporter;

import jakarta.inject.Inject;

public class CsvExporter implements ReportExporter {

    private final Set<CsvDialect> dialects;

    /** Live view: dialects contributed by sub-plugins, whenever they are active. */
    @Inject
    public CsvExporter(Set<CsvDialect> dialects) {
        this.dialects = dialects;
    }

    @Override
    public String format() {
        return "csv";
    }

    @Override
    public String export(List<String> header, List<List<String>> rows) {
        String separator = dialects.stream().findFirst().map(CsvDialect::separator).orElse(",");
        List<String> lines = new ArrayList<>();
        lines.add(String.join(separator, header));
        rows.forEach(row -> lines.add(String.join(separator, row)));
        return String.join("\n", lines);
    }
}
