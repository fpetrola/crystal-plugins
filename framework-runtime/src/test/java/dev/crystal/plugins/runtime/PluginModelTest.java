package dev.crystal.plugins.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The model of a plugin panel: plugins with their content, and the role tree with visibility. */
class PluginModelTest {

    @TempDir
    Path repo;

    private static String exporter(String pkg, String annotations) {
        return """
                package acme.%s;
                import dev.crystal.plugins.runtime.fixtures.ReportExporter;
                import java.util.List;
                %s
                public class Exporter implements ReportExporter {
                    public String format() { return "%s"; }
                    public String export(List<String> rows) { return ""; }
                }
                """.formatted(pkg, annotations, pkg);
    }

    @Test
    void pluginsAndRolesDescribeTheWholeTree() {
        Path multi = PluginJars.plugin("multi", "1.0.0").withProcessor()
                .source("acme.multi.Dialect", """
                        package acme.multi;
                        @dev.crystal.plugins.api.RoleInterface
                        public interface Dialect { String separator(); }
                        """)
                .source("acme.multi.Exporter", exporter("multi", "")).buildInto(repo);
        PluginJars.plugin("tsv", "1.0.0").withProcessor().dependsOn("multi@1.0.0", multi)
                .source("acme.tsv.Tab", """
                        package acme.tsv;
                        public class Tab implements acme.multi.Dialect { public String separator() { return "\\t"; } }
                        """).buildInto(repo);
        PluginJars.plugin("fast", "1.0.0").withProcessor()
                .source("acme.fast.Exporter", exporter("fast", "@dev.crystal.plugins.api.Replaces(\"multi\")"))
                .buildInto(repo);

        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            plugins.installAll();
            plugins.start();

            Map<String, PluginInfo> byId = plugins.plugins().stream()
                    .collect(Collectors.toMap(PluginInfo::id, p -> p));
            PluginInfo parent = byId.get("multi");
            assertEquals(List.of("acme.multi.Dialect"), parent.definesRoles());
            assertEquals(List.of(new PluginInfo.ExtensionInfo("acme.multi.Exporter",
                    List.of("dev.crystal.plugins.runtime.fixtures.ReportExporter"), List.of())), parent.extensions());
            assertEquals(List.of("multi@1.0.0"), byId.get("tsv").dependencies());
            assertEquals(List.of("multi"), byId.get("fast").extensions().get(0).replaces());

            assertEquals(List.of(
                    new RoleInfo("acme.multi.Dialect", Optional.of("multi"),
                            List.of(new RoleInfo.Implementation("tsv", "acme.tsv.Tab", true))),
                    new RoleInfo("dev.crystal.plugins.runtime.fixtures.ReportExporter", Optional.empty(), List.of(
                            new RoleInfo.Implementation("fast", "acme.fast.Exporter", true),
                            new RoleInfo.Implementation("multi", "acme.multi.Exporter", false)))),
                    plugins.roleTree(), "who defines each role, who implements it, and what @Replaces hides");
        }
    }
}
