package com.example.markdown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.reports.ReportExporter;

public class MarkdownExporter implements ReportExporter {

    @Override
    public String format() {
        return "markdown";
    }

    @Override
    public String export(List<String> header, List<List<String>> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(row(header));
        lines.add(row(Collections.nCopies(header.size(), "---")));
        rows.forEach(r -> lines.add(row(r)));
        return String.join("\n", lines);
    }

    private static String row(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }
}
