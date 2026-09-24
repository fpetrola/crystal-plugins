package dev.crystal.plugins.swing;

import java.util.List;
import java.util.Optional;

import javax.swing.Icon;
import java.util.Set;

import javax.swing.tree.DefaultMutableTreeNode;

import dev.crystal.plugins.api.PluginArtifact;
import dev.crystal.plugins.api.PluginDescription;
import dev.crystal.plugins.runtime.PluginInfo;
import dev.crystal.plugins.runtime.PluginService;
import dev.crystal.plugins.runtime.RoleInfo;

/**
 * Builds the two trees of {@link PluginsPanel} from {@link PluginService}'s model. No widgets here, so it runs
 * (and is tested) headless.
 */
public final class PluginTrees {

    /** What a tree node shows, and the plugin it is about (null for nodes about no single plugin). */
    public record Node(String text, String pluginId, String tooltip, Icon icon) {

        public Node(String text, String pluginId, String tooltip) {
            this(text, pluginId, tooltip, null);
        }

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
            PluginIcons.Origin origin = plugins.isBundled(info.id()) ? PluginIcons.Origin.BUNDLED
                    : PluginIcons.Origin.CATALOG;
            tooltip.append(origin == PluginIcons.Origin.BUNDLED ? "\nBundled with the application" : "");
            DefaultMutableTreeNode plugin = new DefaultMutableTreeNode(new Node(text.toString(), info.id(),
                    tooltip.isEmpty() ? null : tooltip.toString().strip(),
                    PluginIcons.plugin(!info.dependencies().isEmpty(), info.status() == PluginInfo.Status.FAILED,
                            pending.contains(info.id()), origin)));
            for (PluginInfo.ExtensionInfo extension : info.extensions()) {
                String replaces = extension.replaces().isEmpty() ? ""
                        : " (replaces " + String.join(", ", extension.replaces()) + ")";
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Node(simple(extension.className())
                        + replaces, info.id(), extension.className(), PluginIcons.extension()));
                extension.roles().forEach(role -> node.add(new DefaultMutableTreeNode(
                        new Node("implements " + simple(role), info.id(), role, PluginIcons.implementsRole()))));
                plugin.add(node);
            }
            for (String role : info.definesRoles()) {
                plugin.add(new DefaultMutableTreeNode(new Node("defines " + simple(role), info.id(), role,
                        PluginIcons.definesRole())));
            }
            root.add(plugin);
        }
        return root;
    }

    /** A plugin the catalog offers, with what it says the plugin brings (empty: it cannot tell). */
    public record Offer(PluginArtifact artifact, String origin, Optional<PluginDescription> description,
                        PluginIcons.Origin kind) {

        public Offer(PluginArtifact artifact, String origin, Optional<PluginDescription> description) {
            this(artifact, origin, description, PluginIcons.Origin.CATALOG);
        }
    }

    /** Available plugin → roles it would implement and define, and what it requires; collapsed like the others. */
    public static DefaultMutableTreeNode available(List<Offer> offers) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Node("Available", null, null));
        for (Offer offer : offers) {
            PluginArtifact a = offer.artifact();
            String text = a.id() + " " + a.version() + (offer.origin().isBlank() ? "" : " — " + offer.origin());
            boolean sub = offer.description().map(d -> !d.dependencies().isEmpty()).orElse(false);
            DefaultMutableTreeNode plugin = new DefaultMutableTreeNode(new Node(text, a.id(), offer.origin(),
                    PluginIcons.plugin(sub, false, false, offer.kind())));
            offer.description().ifPresent(d -> {
                d.implementsRoles().forEach(role -> plugin.add(new DefaultMutableTreeNode(
                        new Node("implements " + simple(role), a.id(), role, PluginIcons.implementsRole()))));
                d.definesRoles().forEach(role -> plugin.add(new DefaultMutableTreeNode(
                        new Node("defines " + simple(role), a.id(), role, PluginIcons.definesRole()))));
                if (!d.dependencies().isEmpty()) {
                    plugin.add(new DefaultMutableTreeNode(new Node("requires " + String.join(", ", d.dependencies()),
                            a.id(), null, PluginIcons.requires())));
                }
            });
            root.add(plugin);
        }
        return root;
    }

    /** Role (and who defines it) → implementations, marking those the conflict resolver hides. */
    public static DefaultMutableTreeNode byRole(PluginService plugins) {
        return byRole(plugins, List.of());
    }

    /**
     * {@link #byRole(PluginService)} plus the plugins the catalog offers for each role, marked "not installed"
     * (only those whose catalog can describe them).
     */
    public static DefaultMutableTreeNode byRole(PluginService plugins, List<Offer> offers) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Node("Roles", null, null));
        for (RoleInfo role : plugins.roleTree()) {
            String definedBy = role.definedBy().map(id -> " (defined by " + id + ")").orElse("");
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Node(simple(role.role()) + definedBy,
                    role.definedBy().orElse(null), role.role(), PluginIcons.role(role.definedBy().isPresent())));
            for (RoleInfo.Implementation implementation : role.implementations()) {
                node.add(new DefaultMutableTreeNode(new Node(simple(implementation.className()) + " — "
                        + implementation.pluginId() + (implementation.visible() ? "" : " (hidden)"),
                        implementation.pluginId(), implementation.className(),
                        PluginIcons.implementation(implementation.visible(), true,
                                PluginService.APPLICATION.equals(implementation.pluginId())
                                        ? PluginIcons.Origin.APPLICATION
                                        : plugins.isBundled(implementation.pluginId()) ? PluginIcons.Origin.BUNDLED
                                        : PluginIcons.Origin.CATALOG))));
            }
            for (Offer offer : offers) {
                if (offer.description().map(d -> d.implementsRoles().contains(role.role())).orElse(false)) {
                    node.add(new DefaultMutableTreeNode(new Node(offer.artifact().id() + " (not installed)",
                            offer.artifact().id(), offer.origin(), PluginIcons.implementation(true, false, offer.kind()))));
                }
            }
            root.add(node);
        }
        // Roles nothing running implements yet, but an available plugin would.
        java.util.Set<String> shown = new java.util.HashSet<>();
        plugins.roleTree().forEach(r -> shown.add(r.role()));
        java.util.Map<String, DefaultMutableTreeNode> extra = new java.util.TreeMap<>();
        for (Offer offer : offers) {
            for (String role : offer.description().map(PluginDescription::implementsRoles).orElse(List.of())) {
                if (!shown.contains(role)) {
                    extra.computeIfAbsent(role, r -> new DefaultMutableTreeNode(new Node(simple(r), null, r,
                                    PluginIcons.role(false))))
                            .add(new DefaultMutableTreeNode(new Node(offer.artifact().id() + " (not installed)",
                                    offer.artifact().id(), offer.origin(),
                                    PluginIcons.implementation(true, false, offer.kind()))));
                }
            }
        }
        extra.values().forEach(root::add);
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
