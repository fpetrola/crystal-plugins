package dev.crystal.plugins.swing;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import dev.crystal.plugins.runtime.PluginService;

/**
 * A reference panel for a Swing application's plugin settings: what each plugin brings, the role tree, and
 * removing a plugin (now if nothing holds it, otherwise on the next start). Everything it shows comes from
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

    public PluginsPanel(PluginService plugins) {
        super(new BorderLayout());
        this.plugins = Objects.requireNonNull(plugins);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Plugins", new JScrollPane(byPlugin));
        tabs.addTab("Roles", new JScrollPane(byRole));
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
