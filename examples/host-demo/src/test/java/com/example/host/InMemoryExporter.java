package com.example.host;

import java.util.List;

import com.example.reports.ReportExporter;

/**
 * A test double implementing a role, as host applications have. Compiling it runs the role processor next to
 * PF4J's own (framework-runtime brings pf4j): both want META-INF/extensions.idx, and that used to fail the
 * build with "Attempt to reopen a file".
 */
public class InMemoryExporter implements ReportExporter {

    @Override
    public String format() {
        return "memory";
    }

    @Override
    public String export(List<String> header, List<List<String>> rows) {
        return header + "" + rows;
    }
}
