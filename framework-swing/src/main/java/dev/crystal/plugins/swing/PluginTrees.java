package dev.crystal.plugins.swing;

import java.util.List;
import java.util.Set;

import javax.swing.tree.DefaultMutableTreeNode;

import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.RoleInfo;

/**
 * Builds the two trees of {@link PluginsPanel} from {@link PluginService}'s model. No widgets here, so it runs
 * (and is tested) headless.
 */
public final class PluginTrees {

    /** What a tree node shows, and the plugin it is about (null for nodes about no single plugin). */
    public record Node(String text, String pluginId, String tooltip) {
        @Override
        public String toString() {
            return text;
        }
    }

    private PluginTrees() {
    }

    /** Plugin → extension → role, with version, state, dependencies, and whether it can be removed now. */
    public static DefaultMutableTreeNode byPlugin(PluginService plugins) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Node("Plugins", null, null));
        Set<String> pending = plugins.pendingRemovals();
        for (PluginInfo info : plugins.plugins()) {
            List<String> heldBy = info.status() == PluginInfo.Status.STARTED ? safeHeldBy(plugins, info.id()) : List.of();
            StringBuilder text = new StringBuilder(info.id()).append(' ').append(info.version());
            if (info.status() != PluginInfo.Status.STARTED) {
                text.append(" [").append(info.status()).append(']');
            }
            if (pending.contains(info.id())) {
                text.append(" (removed on next start)");
            } else if (!heldBy.isEmpty()) {
                text.append(" (in use)");
            }
            StringBuilder tooltip = new StringBuilder();
            info.failure().ifPresent(f -> tooltip.append("Failed: ").append(f.getMessage()).append('\n'));
            if (!info.dependencies().isEmpty()) {
                tooltip.append("Requires ").append(String.join(", ", info.dependencies())).append('\n');
            }
            if (!heldBy.isEmpty()) {
                tooltip.append("Held by ").append(String.join(", ", heldBy)).append('\n');
            }
            info.apiVersion().ifPresent(v -> tooltip.append("Built for framework-api ").append(v));
            DefaultMutableTreeNode plugin = new DefaultMutableTreeNode(new Node(text.toString(), info.id(),
                    tooltip.isEmpty() ? null : tooltip.toString().strip()));
            for (PluginInfo.ExtensionInfo extension : info.extensions()) {
                String replaces = extension.replaces().isEmpty() ? ""
                        : " (replaces " + String.join(", ", extension.replaces()) + ")";
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Node(simple(extension.className())
                        + replaces, info.id(), extension.className()));
                extension.roles().forEach(role -> node.add(new DefaultMutableTreeNode(
                        new Node("implements " + simple(role), info.id(), role))));
                plugin.add(node);
            }
            for (String role : info.definesRoles()) {
                plugin.add(new DefaultMutableTreeNode(new Node("defines " + simple(role), info.id(), role)));
            }
            root.add(plugin);
        }
        return root;
    }

    /** Role (and who defines it) → implementations, marking those the conflict resolver hides. */
    public static DefaultMutableTreeNode byRole(PluginService plugins) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Node("Roles", null, null));
        for (RoleInfo role : plugins.roleTree()) {
            String definedBy = role.definedBy().map(id -> " (defined by " + id + ")").orElse("");
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Node(simple(role.role()) + definedBy,
                    role.definedBy().orElse(null), role.role()));
            for (RoleInfo.Implementation implementation : role.implementations()) {
                node.add(new DefaultMutableTreeNode(new Node(simple(implementation.className()) + " — "
                        + implementation.pluginId() + (implementation.visible() ? "" : " (hidden)"),
                        implementation.pluginId(), implementation.className())));
            }
            root.add(node);
        }
        return root;
    }

    private static List<String> safeHeldBy(PluginService plugins, String id) {
        try {
            return plugins.heldBy(id);
        } catch (IllegalArgumentException notLoaded) {
            return List.of();
        }
    }

    static String simple(String className) {
        int dot = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return className.substring(dot + 1);
    }
}
