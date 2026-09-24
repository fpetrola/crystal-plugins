package dev.crystal.plugins.swing;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginService;

/**
 * A reference panel for a Swing application's plugin settings. The installed plugins and what the catalog (the
 * service's {@code PluginSource}) offers sit side by side, with buttons to move the selected ones from one to
 * the other: installing (with the dependencies they lack; they start at once) and removing (now if nothing
 * holds them, otherwise on the next start). Both lists take several selections. A second tab shows the role
 * tree. Everything it shows comes from {@link PluginService}; drop it in a dialog or a tab:
 *
 * <pre>{@code
 * dialog.add(new PluginsPanel(plugins));
 * }</pre>
 *
 * The catalog is asked the first time the panel is shown, and again after a removal (a removed plugin is
 * offered again); both off the event dispatch thread, because it may go to the network. Call the panel from
 * the event dispatch thread, like any Swing component.
 */
public class PluginsPanel extends JPanel {

    private final PluginService plugins;
    private final JTree installed = tree();
    private final JTree byRole = tree();
    /** What the catalog offers now, not installed; the available tree shows it. */
    private List<PluginTrees.Offer> offered = List.of();
    private final JTree available = tree();
    private final JButton install = new JButton("\u2190 Install", PluginIcons.install());
    private final JButton remove = new JButton("Remove \u2192", PluginIcons.remove());
    private final JLabel status = new JLabel(" ");

    public PluginsPanel(PluginService plugins) {
        super(new BorderLayout());
        this.plugins = Objects.requireNonNull(plugins);

        installed.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        installed.addTreeSelectionListener(e -> remove.setEnabled(!selectedPlugins().isEmpty()));
        available.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        available.addTreeSelectionListener(e -> install.setEnabled(!selectedIds(available).isEmpty()));
        install.setEnabled(false);
        remove.setEnabled(false);
        install.addActionListener(e -> {
            List<String> ids = selectedIds(available);
            if (!ids.isEmpty()) {
                background("Installing " + String.join(", ", ids) + "...", () -> install(ids), this::installed);
            }
        });
        remove.addActionListener(e -> {
            removeSelected();
            recheck();
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Plugins", pluginsTab());
        tabs.addTab("Roles", new JScrollPane(byRole));
        add(tabs, BorderLayout.CENTER);

        JButton refresh = new JButton("Refresh", PluginIcons.check());
        refresh.addActionListener(e -> refresh());
        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.CENTER);
        south.add(refresh, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        refresh();
        // Changes made elsewhere (code, another window) show up here too.
        plugins.onChange(() -> SwingUtilities.invokeLater(this::refresh));
        // The catalog is asked when the panel is first shown, not when it is built.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && offered.isEmpty()) {
                recheck();
            }
        });
    }

    private JPanel pluginsTab() {
        JPanel left = titled("Installed", new JScrollPane(installed));
        JButton check = new JButton("Check", PluginIcons.check());
        check.setToolTipText("Ask the catalog again");
        check.addActionListener(e -> recheck());
        JPanel checkRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        checkRow.add(check);
        JPanel right = titled("Available", new JScrollPane(available));
        right.add(checkRow, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 8));
        buttons.add(install);
        buttons.add(remove);

        // Installed | buttons | Available, the two lists sharing the width equally whatever their contents.
        left.setPreferredSize(new Dimension(260, 240));
        right.setPreferredSize(new Dimension(260, 240));
        JPanel tab = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        c.weightx = 1;
        tab.add(left, c);
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 6, 0, 6);
        tab.add(buttons, c);
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        tab.add(right, c);
        return tab;
    }

    private static JPanel titled(String title, JScrollPane content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Rebuilds both trees from the service's current state, keeping which plugins are selected and expanded.
     * Plugins start collapsed: one line each, opened on demand to see extensions and roles.
     */
    public void refresh() {
        List<String> selected = selectedPlugins();
        Set<String> expanded = expanded(installed, PluginTrees.Node::pluginId);
        show(installed, PluginTrees.grouped(PluginTrees.byPlugin(plugins)), expanded, PluginTrees.Node::pluginId);
        Set<String> roles = expanded(byRole, PluginTrees.Node::tooltip);
        List<String> loaded = plugins.plugins().stream().map(PluginInfo::id).toList();
        offered = offered.stream().filter(o -> !loaded.contains(o.artifact().id())).toList();
        List<String> availableSelected = selectedIds(available);
        show(available, PluginTrees.grouped(PluginTrees.available(offered)), expanded(available, PluginTrees.Node::pluginId),
                PluginTrees.Node::pluginId);
        selectIn(available, availableSelected);
        show(byRole, PluginTrees.byRole(plugins, offered), roles, PluginTrees.Node::tooltip);
        select(selected.toArray(String[]::new));
        remove.setEnabled(!selectedPlugins().isEmpty());
    }

    /**
     * Removes the plugins selected in the installed list: each at once if nothing holds it, otherwise on the
     * next start.
     *
     * @return what happened, also shown in the panel's status line
     */
    public String removeSelected() {
        List<String> ids = selectedPlugins();
        if (ids.isEmpty()) {
            return "";
        }
        List<String> removed = new ArrayList<>();
        List<String> later = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String id : ids) {
            if (removed.contains(id) || plugins.plugins().stream().noneMatch(p -> p.id().equals(id))) {
                continue; // went with a plugin it depended on
            }
            try {
                if (plugins.heldBy(id).isEmpty()) {
                    plugins.uninstall(id).forEach(p -> removed.add(p.id()));
                } else {
                    later.addAll(plugins.uninstallOnNextStart(id));
                }
            } catch (RuntimeException e) {
                errors.add("Cannot remove " + id + ": " + e.getMessage());
            }
        }
        List<String> parts = new ArrayList<>();
        if (!removed.isEmpty()) {
            parts.add("Removed " + String.join(", ", removed));
        }
        if (!later.isEmpty()) {
            parts.add(String.join(", ", new LinkedHashSet<>(later)) + " in use: removed on next start");
        }
        parts.addAll(errors);
        String message = String.join("; ", parts);
        status.setText(message);
        refresh();
        return message;
    }

    /**
     * Asks the catalog what it offers that is not installed (it may use the network) and lists it as
     * available. The panel does this off the event dispatch thread.
     *
     * @return the offered plugins
     */
    public List<PluginArtifact> checkAvailable() {
        List<PluginTrees.Offer> found = offers();
        showAvailable(found);
        return found.stream().map(PluginTrees.Offer::artifact).toList();
    }

    /** The catalog's offer, each with its origin and description (all may use the network). */
    private List<PluginTrees.Offer> offers() {
        return plugins.available().stream()
                .map(a -> {
                    String origin = plugins.origin(a);
                    PluginIcons.Origin kind = plugins.isBundled(a) ? PluginIcons.Origin.BUNDLED
                            : origin.startsWith("directory(") ? PluginIcons.Origin.LOCAL : PluginIcons.Origin.CATALOG;
                    return new PluginTrees.Offer(a, origin, plugins.describe(a), kind);
                }).toList();
    }

    /**
     * Installs the plugins selected in the available list, with the dependencies they lack; they start at once
     * and are selected in the installed list. The Install button does this off the event dispatch thread.
     *
     * @return what happened, also shown in the status line
     */
    public String installSelected() {
        List<String> ids = selectedIds(available);
        if (ids.isEmpty()) {
            return "";
        }
        Result result = install(ids);
        installed(result);
        return result.message();
    }

    /** Selects plugins {@code ids} in the available list (for callers and tests). */
    public void selectAvailable(String... ids) {
        selectIn(available, Arrays.asList(ids));
    }

    /** Selects plugins {@code ids} in the installed list, scrolling to the first (for callers and tests). */
    public void select(String... ids) {
        selectIn(installed, Arrays.asList(ids));
    }

    private static void selectIn(JTree tree, List<String> wanted) {
        List<TreePath> paths = new ArrayList<>();
        for (DefaultMutableTreeNode child : top((DefaultMutableTreeNode) tree.getModel().getRoot())) {
            if (child.getUserObject() instanceof PluginTrees.Node node && wanted.contains(node.pluginId())) {
                paths.add(new TreePath(child.getPath()));
            }
        }
        tree.setSelectionPaths(paths.toArray(TreePath[]::new));
        if (!paths.isEmpty()) {
            tree.scrollPathToVisible(paths.get(0));
        }
    }

    /** The installed list, for tests. */
    JTree installedTree() {
        return installed;
    }

    private record Result(String message, List<String> installed) {
    }

    private Result install(List<String> ids) {
        List<String> added = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (String id : ids) {
            try {
                List<PluginInfo> infos = plugins.install(id);
                if (infos.isEmpty()) {
                    parts.add(id + " was already installed");
                }
                infos.forEach(p -> added.add(p.id()));
            } catch (RuntimeException e) {
                parts.add("Cannot install " + id + ": " + e.getMessage());
            }
        }
        if (!added.isEmpty()) {
            parts.add(0, "Installed " + String.join(", ", added));
        }
        return new Result(String.join("; ", parts), added);
    }

    /** Shows an installation: the new plugins leave the available list and are selected where they arrived. */
    private void installed(Result result) {
        status.setText(result.message());
        refresh();
        select(result.installed().toArray(String[]::new));
    }

    private void recheck() {
        background("Checking the catalog...", this::offers, found -> {
            showAvailable(found);
            status.setText(found.isEmpty() ? "The catalog offers nothing that is not installed"
                    : found.size() + " available");
        });
    }

    private void showAvailable(List<PluginTrees.Offer> found) {
        offered = List.copyOf(found);
        refresh();
        install.setEnabled(!selectedIds(available).isEmpty());
    }

    /** Runs {@code work} off the event dispatch thread and hands its result to {@code done} back on it. */
    private <T> void background(String working, java.util.function.Supplier<T> work,
                                java.util.function.Consumer<T> done) {
        status.setText(working);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() {
                return work.get();
            }

            @Override
            protected void done() {
                try {
                    done.accept(get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    status.setText("Failed: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private List<String> selectedPlugins() {
        return selectedIds(installed);
    }

    /** The plugin ids of the top-level nodes selected in {@code tree} (or of the nodes under them). */
    private static List<String> selectedIds(JTree tree) {
        TreePath[] paths = tree.getSelectionPaths();
        Set<String> ids = new LinkedHashSet<>();
        if (paths != null) {
            for (TreePath path : paths) {
                DefaultMutableTreeNode last = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (last.getUserObject() instanceof PluginTrees.Node n && n.group()) {
                    // A group stands for all its plugins.
                    for (int i = 0; i < last.getChildCount(); i++) {
                        if (((DefaultMutableTreeNode) last.getChildAt(i)).getUserObject()
                                instanceof PluginTrees.Node child && child.pluginId() != null) {
                            ids.add(child.pluginId());
                        }
                    }
                    continue;
                }
                for (int i = 1; i < path.getPathCount(); i++) {
                    if (((DefaultMutableTreeNode) path.getPathComponent(i)).getUserObject()
                            instanceof PluginTrees.Node node && node.pluginId() != null) {
                        ids.add(node.pluginId());
                        break;
                    }
                }
            }
        }
        return List.copyOf(ids);
    }

    /** The keys of the expanded top-level nodes, to open them again after a rebuild. */
    private static Set<String> expanded(JTree tree, Function<PluginTrees.Node, String> key) {
        Set<String> expanded = new LinkedHashSet<>();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (child.getUserObject() instanceof PluginTrees.Node n && n.group()
                    && !tree.isExpanded(new TreePath(child.getPath()))) {
                expanded.add("collapsed group:" + n.text()); // groups start open: remember the closed ones
            }
        }
        for (DefaultMutableTreeNode child : top(root)) {
            if (tree.isExpanded(new TreePath(child.getPath())) && child.getUserObject() instanceof PluginTrees.Node n) {
                expanded.add(key.apply(n));
            }
        }
        return expanded;
    }

    /** The top-level items of a tree: the root's children, and the children of its groups instead of the groups. */
    private static List<DefaultMutableTreeNode> top(DefaultMutableTreeNode root) {
        List<DefaultMutableTreeNode> result = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (child.getUserObject() instanceof PluginTrees.Node n && n.group()) {
                for (int j = 0; j < child.getChildCount(); j++) {
                    result.add((DefaultMutableTreeNode) child.getChildAt(j));
                }
            } else {
                result.add(child);
            }
        }
        return result;
    }

    /** Shows {@code root} with its top-level nodes collapsed, except those that were expanded before. */
    private static void show(JTree tree, DefaultMutableTreeNode root, Set<String> expanded,
                             Function<PluginTrees.Node, String> key) {
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (child.getUserObject() instanceof PluginTrees.Node n && n.group()
                    && !expanded.contains("collapsed group:" + n.text())) {
                tree.expandPath(new TreePath(child.getPath()));
            }
        }
        for (DefaultMutableTreeNode child : top(root)) {
            if (child.getUserObject() instanceof PluginTrees.Node n && expanded.contains(key.apply(n))) {
                tree.expandPath(new TreePath(child.getPath()));
            }
        }
    }

    /** Name first and bold; prefix, detail and flag smaller and muted (or colored), so the name stands out. */
    static String html(PluginTrees.Label label, boolean selected) {
        // Mid grey reads on light and dark themes, selected or not.
        String muted = "#888888";
        StringBuilder out = new StringBuilder("<html>");
        if (label.prefix() != null) {
            out.append("<font color='").append(muted).append("'>").append(escape(label.prefix())).append("</font> ");
        }
        out.append("<b>").append(escape(label.name())).append("</b>");
        if (label.detail() != null && !label.detail().isBlank()) {
            out.append(" <font size='-1' color='").append(muted).append("'>(").append(escape(label.detail()))
                    .append(")</font>");
        }
        if (label.flag() != null) {
            String color = switch (label.flag().kind()) {
                case IN_USE -> "#2f6fd0";
                case PENDING -> "#c77800";
                case FAILED -> "#d03030";
                case HIDDEN, NOT_INSTALLED -> "#999999";
            };
            out.append(" <font size='-1' color='").append(color).append("'><i>").append(escape(label.flag().text()))
                    .append("</i></font>");
        }
        return out.append("</html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JTree tree() {
        JTree tree = new JTree(new DefaultMutableTreeNode()) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent event) {
                TreePath path = getPathForLocation(event.getX(), event.getY());
                if (path == null) {
                    return null;
                }
                Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                return value instanceof PluginTrees.Node node ? node.tooltip() : null;
            }
        };
        tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(JTree t, Object value, boolean selected,
                                                                   boolean expanded, boolean leaf, int row,
                                                                   boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                if (((DefaultMutableTreeNode) value).getUserObject() instanceof PluginTrees.Node node) {
                    if (node.icon() != null) {
                        setIcon(node.icon());
                    }
                    if (node.label() != null) {
                        setText(html(node.label(), selected));
                    }
                }
                return this;
            }
        });
        tree.setRowHeight(20);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        ToolTipManager.sharedInstance().registerComponent(tree);
        return tree;
    }
}
