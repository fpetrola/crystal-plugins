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
    public record Node(String text, String pluginId, String tooltip, Icon icon, Label label, boolean group) {

        public Node(String text, String pluginId, String tooltip) {
            this(text, pluginId, tooltip, null, null, false);
        }

        public Node(String text, String pluginId, String tooltip, Icon icon) {
            this(text, pluginId, tooltip, icon, null, false);
        }

        /** This node with how the panel shows it: name first and bold, the rest smaller. */
        public Node label(String prefix, String name, String detail, Flag flag) {
            return new Node(text, pluginId, tooltip, icon, new Label(prefix, name, detail, flag), group);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** How a node is shown: a muted {@code prefix}, the {@code name} in bold, a small {@code detail}, a {@code flag}. */
    public record Label(String prefix, String name, String detail, Flag flag) {
    }

    /** A state worth noticing, shown small and colored after the name. */
    public record Flag(String text, Kind kind) {
        public enum Kind { IN_USE, PENDING, FAILED, HIDDEN, NOT_INSTALLED }
    }

    private static Flag flag(String text, Flag.Kind kind) {
        return new Flag(text, kind);
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
                            pending.contains(info.id()), origin))
                    .label(null, info.name().orElse(info.id()),
                            info.name().isPresent() ? info.id() + " · " + info.version() : info.version(),
                            info.status() == PluginInfo.Status.FAILED ? flag("failed", Flag.Kind.FAILED)
                                    : info.status() != PluginInfo.Status.STARTED
                                    ? flag(info.status().name().toLowerCase(), Flag.Kind.PENDING)
                                    : pending.contains(info.id()) ? flag("removed on next start", Flag.Kind.PENDING)
                                    : !heldBy.isEmpty() ? flag("in use", Flag.Kind.IN_USE) : null));
            for (PluginInfo.ExtensionInfo extension : info.extensions()) {
                String replaces = extension.replaces().isEmpty() ? ""
                        : " (replaces " + String.join(", ", extension.replaces()) + ")";
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Node(simple(extension.className())
                        + replaces, info.id(), extension.className(), PluginIcons.extension())
                        .label(null, simple(extension.className()), extension.replaces().isEmpty() ? null
                                : "replaces " + String.join(", ", extension.replaces()), null));
                extension.roles().forEach(role -> node.add(new DefaultMutableTreeNode(
                        new Node("implements " + simple(role), info.id(), role, PluginIcons.implementsRole())
                                .label("implements", simple(role), null, null))));
                plugin.add(node);
            }
            for (String role : info.definesRoles()) {
                plugin.add(new DefaultMutableTreeNode(new Node("defines " + simple(role), info.id(), role,
                        PluginIcons.definesRole()).label("defines", simple(role), null, null)));
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
                    PluginIcons.plugin(sub, false, false, offer.kind()))
                    .label(null, offer.description().map(PluginDescription::name).orElse(a.id()),
                            (offer.description().map(PluginDescription::name).isPresent() ? a.id() + " · " : "")
                                    + a.version() + (offer.origin().isBlank() ? "" : " · " + offer.origin()), null));
            offer.description().ifPresent(d -> {
                d.implementsRoles().forEach(role -> plugin.add(new DefaultMutableTreeNode(
                        new Node("implements " + simple(role), a.id(), role, PluginIcons.implementsRole())
                                .label("implements", simple(role), null, null))));
                d.definesRoles().forEach(role -> plugin.add(new DefaultMutableTreeNode(
                        new Node("defines " + simple(role), a.id(), role, PluginIcons.definesRole())
                                .label("defines", simple(role), null, null))));
                if (!d.dependencies().isEmpty()) {
                    plugin.add(new DefaultMutableTreeNode(new Node("requires " + String.join(", ", d.dependencies()),
                            a.id(), null, PluginIcons.requires())
                            .label("requires", String.join(", ", d.dependencies()), null, null)));
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
                    role.definedBy().orElse(null), role.role(), PluginIcons.role(role.definedBy().isPresent()))
                    .label(null, simple(role.role()), role.definedBy().map(id -> "defined by " + id).orElse(null),
                            null));
            for (RoleInfo.Implementation implementation : role.implementations()) {
                node.add(new DefaultMutableTreeNode(new Node(simple(implementation.className()) + " — "
                        + implementation.pluginId() + (implementation.visible() ? "" : " (hidden)"),
                        implementation.pluginId(), implementation.className(),
                        PluginIcons.implementation(implementation.visible(), true,
                                PluginService.APPLICATION.equals(implementation.pluginId())
                                        ? PluginIcons.Origin.APPLICATION
                                        : plugins.isBundled(implementation.pluginId()) ? PluginIcons.Origin.BUNDLED
                                        : PluginIcons.Origin.CATALOG))
                        .label(null, simple(implementation.className()), implementation.pluginId(),
                                implementation.visible() ? null : flag("hidden", Flag.Kind.HIDDEN))));
            }
            for (Offer offer : offers) {
                if (offer.description().map(d -> d.implementsRoles().contains(role.role())).orElse(false)) {
                    node.add(new DefaultMutableTreeNode(new Node(offer.artifact().id() + " (not installed)",
                            offer.artifact().id(), offer.origin(), PluginIcons.implementation(true, false, offer.kind()))
                            .label(null, offer.artifact().id(), offer.artifact().version(),
                                    flag("not installed", Flag.Kind.NOT_INSTALLED))));
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
                                    PluginIcons.role(false)).label(null, simple(r), null, null)))
                            .add(new DefaultMutableTreeNode(new Node(offer.artifact().id() + " (not installed)",
                                    offer.artifact().id(), offer.origin(),
                                    PluginIcons.implementation(true, false, offer.kind()))
                                    .label(null, offer.artifact().id(), offer.artifact().version(),
                                            flag("not installed", Flag.Kind.NOT_INSTALLED))));
                }
            }
        }
        extra.values().forEach(root::add);
        return root;
    }

    /**
     * Puts the top-level plugins of {@code root} whose ids share a prefix ({@code device-} in {@code device-beeper})
     * under a node for that prefix, when at least two do. Knows no prefix in particular: it only reads the ids.
     */
    public static DefaultMutableTreeNode grouped(DefaultMutableTreeNode root) {
        return grouped(root, null);
    }

    /** The prefix of {@code id} up to its first dash, or null. */
    public static String prefix(String id) {
        return id != null && id.indexOf('-') > 0 ? id.substring(0, id.indexOf('-')) : null;
    }

    /**
     * {@link #grouped(DefaultMutableTreeNode)} with the prefixes decided elsewhere: every plugin whose prefix is in
     * {@code prefixes} goes under its group, even alone. The panel passes the prefixes shared by at least two
     * plugins across both of its lists, so moving one plugin to the other side keeps it grouped (and short).
     * Null: decide from this tree only.
     */
    public static DefaultMutableTreeNode grouped(DefaultMutableTreeNode root, Set<String> prefixes) {
        java.util.Map<String, List<DefaultMutableTreeNode>> byPrefix = new java.util.LinkedHashMap<>();
        List<DefaultMutableTreeNode> children = new java.util.ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            children.add(child);
            if (child.getUserObject() instanceof Node n && n.pluginId() != null && n.pluginId().indexOf('-') > 0) {
                byPrefix.computeIfAbsent(n.pluginId().substring(0, n.pluginId().indexOf('-')),
                        k -> new java.util.ArrayList<>()).add(child);
            }
        }
        DefaultMutableTreeNode result = new DefaultMutableTreeNode(root.getUserObject());
        java.util.Map<String, DefaultMutableTreeNode> groups = new java.util.TreeMap<>();
        byPrefix.forEach((prefix, members) -> {
            if (prefixes == null ? members.size() >= 2 : prefixes.contains(prefix)) {
                Node group = new Node(prefix, null, members.size() + " plugins", PluginIcons.group(), null, true)
                        .label(null, prefix, String.valueOf(members.size()), null);
                groups.put(prefix, new DefaultMutableTreeNode(group));
            }
        });
        groups.values().forEach(result::add);
        for (DefaultMutableTreeNode child : children) {
            String prefix = child.getUserObject() instanceof Node n && n.pluginId() != null
                    && n.pluginId().indexOf('-') > 0 ? n.pluginId().substring(0, n.pluginId().indexOf('-')) : null;
            if (prefix != null && groups.containsKey(prefix)) {
                // The group already says the prefix: a plugin without a name of its own shows the rest of its id.
                if (child.getUserObject() instanceof Node n && n.label() != null
                        && n.label().name().equals(n.pluginId())) {
                    child.setUserObject(n.label(n.label().prefix(), n.pluginId().substring(prefix.length() + 1),
                            n.pluginId() + (n.label().detail() == null ? "" : " · " + n.label().detail()),
                            n.label().flag()));
                }
                groups.get(prefix).add(child);
            } else {
                result.add(child);
            }
        }
        return result;
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
