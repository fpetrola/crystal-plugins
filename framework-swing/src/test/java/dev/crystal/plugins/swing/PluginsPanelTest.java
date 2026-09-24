package dev.crystal.plugins.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.SwingUtilities;
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

    /** Runs {@code test} on the event dispatch thread, as the panel expects (its own refreshes run there too). */
    private static void onEdt(Runnable test) throws Exception {
        try {
            SwingUtilities.invokeAndWait(test);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw (Exception) e.getCause();
        }
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
    void theCatalogOffersWhatIsNotInstalledAndInstallsIt() throws Exception {
        PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", exporter("csv", ""))
                .buildInto(repo);
        PluginJars.plugin("md", "2.0.0").withProcessor().source("acme.md.Exporter", exporter("md", ""))
                .buildInto(repo);
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            plugins.start();
            onEdt(() -> {
                PluginsPanel panel = new PluginsPanel(plugins);
                assertEquals(List.of("csv", "md"), panel.checkAvailable().stream().map(a -> a.id()).toList(),
                        "nothing installed yet: the catalog offers both");
                panel.selectAvailable("md");
                assertEquals("Installed md", panel.installSelected());
            });

            assertEquals(List.of("csv"), plugins.available().stream().map(a -> a.id()).toList());
            assertEquals("directory(" + repo + ")", plugins.origin(plugins.available().get(0)),
                    "by default, the source's own description");
            assertTrue(lines(PluginTrees.byPlugin(plugins)).contains("md 2.0.0"), "running at once");
        }
    }

    @Test
    void removingIsImmediateWhenFreeAndDeferredWhenHeld() throws Exception {
        try (PluginService plugins = started()) {
            Screen screen = plugins.create(Screen.class);
            onEdt(() -> {
                PluginsPanel panel = new PluginsPanel(plugins);
                panel.select("fast");
                assertEquals("fast in use: removed on next start", panel.removeSelected());
                assertTrue(lines(PluginTrees.byPlugin(plugins)).contains("fast 1.0.0 (removed on next start)"));

                panel.select("tsv");
                assertEquals("Removed tsv", panel.removeSelected());
            });
            assertEquals("fast", screen.exporter.format());
        }
    }

    @Test
    void severalMoveAtOnceAndTheNewOnesAreSelectedInACollapsedList() throws Exception {
        PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", exporter("csv", ""))
                .buildInto(repo);
        PluginJars.plugin("md", "2.0.0").withProcessor().source("acme.md.Exporter", exporter("md", ""))
                .buildInto(repo);
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            plugins.start();
            onEdt(() -> {
                PluginsPanel panel = new PluginsPanel(plugins);
                panel.checkAvailable();

                panel.selectAvailable("csv", "md");
                assertEquals("Installed csv, md", panel.installSelected());
                assertEquals(2, panel.installedTree().getRowCount(), "one line per plugin: children collapsed");
                assertEquals(2, panel.installedTree().getSelectionCount(), "what just arrived is selected");

                panel.installedTree().expandRow(0);
                panel.refresh();
                assertTrue(panel.installedTree().isExpanded(0), "a refresh keeps what the user opened");
                assertEquals(2, panel.installedTree().getSelectionCount(), "and what the user selected");

                panel.select("csv", "md");
                assertEquals("Removed csv, md", panel.removeSelected());
            });
            assertTrue(plugins.plugins().isEmpty());
        }
    }

    @Test
    void theCatalogTellsWhatANotInstalledPluginBrings() throws Exception {
        PluginJars.plugin("csv", "1.0.0").withProcessor().source("acme.csv.Exporter", exporter("csv", ""))
                .buildInto(repo);
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            plugins.start();
            var csv = plugins.available().get(0);
            assertEquals(List.of(ReportExporter.class.getName()),
                    plugins.describe(csv).orElseThrow().implementsRoles(), "read from the jar, not installed");

            List<PluginTrees.Offer> offers = List.of(new PluginTrees.Offer(csv, "", plugins.describe(csv)));
            assertTrue(lines(PluginTrees.available(offers)).contains("  implements ReportExporter"));
            assertTrue(lines(PluginTrees.byRole(plugins, offers)).contains("  csv (not installed)"),
                    lines(PluginTrees.byRole(plugins, offers)).toString());
        }
    }

    @Test
    void pluginsShowTheirNameAndGroupByIdPrefix() throws Exception {
        for (String id : List.of("device-beeper", "device-tape", "tool-csv")) {
            PluginJars.plugin(id, "1.0.0").named(id.equals("device-beeper") ? "Beeper" : null).withProcessor()
                    .source("acme." + id.replace('-', '_') + ".Exporter", exporter(id.replace('-', '_'), ""))
                    .buildInto(repo);
        }
        try (PluginService plugins = PluginService.builder().source(PluginSources.directory(repo)).build()) {
            plugins.installAll();
            plugins.start();
            assertEquals(java.util.Optional.of("Beeper"), plugins.plugins().stream()
                    .filter(p -> p.id().equals("device-beeper")).findFirst().orElseThrow().name());

            DefaultMutableTreeNode grouped = PluginTrees.grouped(PluginTrees.byPlugin(plugins));
            List<String> top = new ArrayList<>();
            for (int i = 0; i < grouped.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) grouped.getChildAt(i);
                top.add(child.getUserObject() + "/" + child.getChildCount());
            }
            assertEquals("device", top.get(0).split("/")[0], top.toString());
            assertEquals(2, ((DefaultMutableTreeNode) grouped.getChildAt(0)).getChildCount(),
                    "the two device- plugins under one node; tool-csv alone stays on top");
            DefaultMutableTreeNode devices = (DefaultMutableTreeNode) grouped.getChildAt(0);
            PluginTrees.Node beeper = null;
            for (int i = 0; i < devices.getChildCount(); i++) {
                PluginTrees.Node n = (PluginTrees.Node) ((DefaultMutableTreeNode) devices.getChildAt(i)).getUserObject();
                if (n.pluginId().equals("device-beeper")) {
                    beeper = n;
                }
            }
            assertEquals("Beeper", beeper.label().name());
            assertEquals("device-beeper · 1.0.0", beeper.label().detail());

            onEdt(() -> {
                PluginsPanel panel = new PluginsPanel(plugins);
                panel.installedTree().setSelectionRow(0); // the group
                String removed = panel.removeSelected();
                assertTrue(removed.contains("device-beeper") && removed.contains("device-tape")
                        && !removed.contains("tool-csv"), "a group stands for all its plugins: " + removed);
            });
        }
    }
}
