package dev.crystal.plugins.swing;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginService;

/**
 * A reference panel for a Swing application's plugin settings: what each plugin brings, the role tree, and
 * removing a plugin (now if nothing holds it, otherwise on the next start), and installing what the catalog
 * (the service's {@code PluginSource}) offers. Everything it shows comes from
 * {@link PluginService}; drop it in a dialog or a tab:
 *
 * <pre>{@code
 * dialog.add(new PluginsPanel(plugins));
 * }</pre>
 *
 * Call it from the event dispatch thread, like any Swing component.
 */
public class PluginsPanel extends JPanel {

    private final PluginService plugins;
    private final JTree byPlugin = tree();
    private final JTree byRole = tree();
    private final JButton remove = new JButton("Remove");
    private final JLabel status = new JLabel(" ");
    private final DefaultListModel<PluginArtifact> offered = new DefaultListModel<>();
    private final JList<PluginArtifact> available = new JList<>(offered);
    private final JButton install = new JButton("Install");

    public PluginsPanel(PluginService plugins) {
        super(new BorderLayout());
        this.plugins = Objects.requireNonNull(plugins);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Plugins", new JScrollPane(byPlugin));
        tabs.addTab("Roles", new JScrollPane(byRole));
        tabs.addTab("Available", availableTab());
        add(tabs, BorderLayout.CENTER);

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        remove.addActionListener(e -> removeSelected());
        remove.setEnabled(false);
        byPlugin.addTreeSelectionListener(e -> remove.setEnabled(selectedPlugin() != null));

        JPanel south = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(refresh);
        buttons.add(remove);
        south.add(status, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        refresh();
        // Changes made elsewhere (code, another window) show up here too.
        plugins.onChange(() -> javax.swing.SwingUtilities.invokeLater(this::refresh));
    }

    /** Rebuilds both trees from the service's current state. */
    public void refresh() {
        show(byPlugin, PluginTrees.byPlugin(plugins));
        show(byRole, PluginTrees.byRole(plugins));
        remove.setEnabled(selectedPlugin() != null);
    }

    /**
     * Removes the selected plugin: at once if nothing holds it, otherwise on the next start.
     *
     * @return what happened, also shown in the panel's status line
     */
    public String removeSelected() {
        String id = selectedPlugin();
        if (id == null) {
            return "";
        }
        String message;
        try {
            if (plugins.heldBy(id).isEmpty()) {
                List<String> removed = plugins.uninstall(id).stream().map(p -> p.id()).toList();
                message = "Removed " + String.join(", ", removed);
            } else {
                List<String> later = plugins.uninstallOnNextStart(id);
                message = String.join(", ", later) + " in use: removed on next start";
            }
        } catch (RuntimeException e) {
            message = "Cannot remove " + id + ": " + e.getMessage();
        }
        status.setText(message);
        refresh();
        return message;
    }

    /**
     * Asks the catalog what it offers that is not installed (it may use the network) and lists it in the
     * "Available" tab. The Check button does this off the event dispatch thread.
     *
     * @return the offered plugins
     */
    public List<PluginArtifact> checkAvailable() {
        List<PluginArtifact> found = plugins.available();
        showAvailable(found);
        return found;
    }

    /**
     * Installs the plugin selected in the "Available" tab, with the dependencies it lacks; it starts at once.
     * The Install button does this off the event dispatch thread.
     *
     * @return what happened, also shown in the status line
     */
    public String installSelected() {
        PluginArtifact selected = available.getSelectedValue();
        if (selected == null) {
            return "";
        }
        String message = install(selected.id());
        showStatus(message);
        return message;
    }

    /** Selects plugin {@code id} in the "Available" tab (for callers and tests). */
    public void selectAvailable(String id) {
        for (int i = 0; i < offered.size(); i++) {
            if (offered.get(i).id().equals(id)) {
                available.setSelectedIndex(i);
                return;
            }
        }
    }

    private String install(String id) {
        try {
            List<PluginInfo> added = plugins.install(id);
            return added.isEmpty() ? id + " was already installed"
                    : "Installed " + String.join(", ", added.stream().map(PluginInfo::id).toList());
        } catch (RuntimeException e) {
            return "Cannot install " + id + ": " + e.getMessage();
        }
    }

    private void showAvailable(List<PluginArtifact> found) {
        offered.clear();
        found.forEach(offered::addElement);
        install.setEnabled(false);
    }

    private void showStatus(String message) {
        status.setText(message);
        refresh();
        List<String> loaded = plugins.plugins().stream().map(PluginInfo::id).toList();
        for (int i = offered.size() - 1; i >= 0; i--) {
            if (loaded.contains(offered.get(i).id())) {
                offered.remove(i);
            }
        }
    }

    private JPanel availableTab() {
        available.setCellRenderer((list, value, index, selected, focus) -> {
            String origin = plugins.origin(value);
            JLabel label = new JLabel(value.id() + " " + value.version() + (origin.isBlank() ? "" : " — " + origin));
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        available.addListSelectionListener(e -> install.setEnabled(available.getSelectedValue() != null));
        install.setEnabled(false);
        JButton check = new JButton("Check");
        check.addActionListener(e -> background("Checking the catalog...", plugins::available, this::showAvailable));
        install.addActionListener(e -> {
            PluginArtifact selected = available.getSelectedValue();
            if (selected != null) {
                background("Installing " + selected.id() + "...", () -> install(selected.id()), this::showStatus);
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(check);
        buttons.add(install);
        JPanel tab = new JPanel(new BorderLayout());
        tab.add(new JScrollPane(available), BorderLayout.CENTER);
        tab.add(buttons, BorderLayout.SOUTH);
        return tab;
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

    /** Selects the node of plugin {@code id} in the plugins tree (for callers and tests). */
    public void select(String id) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) byPlugin.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (child.getUserObject() instanceof PluginTrees.Node node && id.equals(node.pluginId())) {
                byPlugin.setSelectionPath(new TreePath(child.getPath()));
                return;
            }
        }
    }

    private String selectedPlugin() {
        TreePath path = byPlugin.getSelectionPath();
        if (path == null || path.getPathCount() < 2) {
            return null;
        }
        Object top = ((DefaultMutableTreeNode) path.getPathComponent(1)).getUserObject();
        return top instanceof PluginTrees.Node node ? node.pluginId() : null;
    }

    private static void show(JTree tree, DefaultMutableTreeNode root) {
        tree.setModel(new DefaultTreeModel(root));
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
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
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        ToolTipManager.sharedInstance().registerComponent(tree);
        return tree;
    }
}
