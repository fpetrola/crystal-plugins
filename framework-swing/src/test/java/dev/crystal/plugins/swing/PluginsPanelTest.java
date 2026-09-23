package dev.crystal.plugins.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.tree.DefaultMutableTreeNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.crystal.plugins.runtime.PluginJars;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.PluginSources;
import dev.crystal.plugins.runtime.fixtures.ReportExporter;
import jakarta.inject.Inject;

class PluginsPanelTest {

    @TempDir
    Path repo;

    /** An application object holding an exporter as a fixed reference. */
    public static final class Screen {
        final ReportExporter exporter;

        @Inject
        public Screen(ReportExporter exporter) {
            this.exporter = exporter;
        }
    }

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

    private PluginService started() {
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
        PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build();
        plugins.installAll();
        plugins.start();
        return plugins;
    }

    private static List<String> lines(DefaultMutableTreeNode root) {
        List<String> lines = new ArrayList<>();
        for (Object o : Collections.list(root.depthFirstEnumeration())) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) o;
            if (node != root) {
                lines.add("  ".repeat(node.getLevel() - 1) + node.getUserObject());
            }
        }
        Collections.reverse(lines);
        return lines;
    }

    @Test
    void theTreesShowPluginsSubPluginsAndWhatIsHidden() {
        try (PluginService plugins = started()) {
            List<String> byPlugin = lines(PluginTrees.byPlugin(plugins));
            assertTrue(byPlugin.contains("multi 1.0.0"), byPlugin.toString());
            assertTrue(byPlugin.contains("  defines Dialect"), byPlugin.toString());
            assertTrue(byPlugin.contains("  Exporter (replaces multi)"), byPlugin.toString());

            List<String> byRole = lines(PluginTrees.byRole(plugins));
            assertTrue(byRole.contains("Dialect (defined by multi)"), byRole.toString());
            assertTrue(byRole.contains("  Tab — tsv"), byRole.toString());
            assertTrue(byRole.contains("  Exporter — multi (hidden)"), byRole.toString());
        }
    }

    @Test
    void removingIsImmediateWhenFreeAndDeferredWhenHeld() {
        try (PluginService plugins = started()) {
            Screen screen = plugins.create(Screen.class);
            PluginsPanel panel = new PluginsPanel(plugins);

            panel.select("fast");
            assertEquals("fast in use: removed on next start", panel.removeSelected());
            assertTrue(lines(PluginTrees.byPlugin(plugins)).contains("fast 1.0.0 (removed on next start)"));

            panel.select("tsv");
            assertEquals("Removed tsv", panel.removeSelected());
            assertEquals("fast", screen.exporter.format());
        }
    }
}
