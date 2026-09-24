package dev.crystal.plugins.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeCellRenderer;

/**
 * Draws a {@link PluginTrees.Node}: its icon, a muted prefix, the name in bold, the detail smaller and muted in
 * parentheses, and a colored flag. It paints itself, with no child components and no HTML: some look and feels
 * (darklaf) paint a renderer without laying it out first, and a renderer made of laid-out parts, or an HTML label,
 * then shows another row's text or nothing at all. Nodes without a label fall back to the standard renderer.
 */
final class NodeRenderer extends JComponent implements TreeCellRenderer {

    private static final Color MUTED = new Color(0x888888);
    private static final int GAP = 4;

    private final DefaultTreeCellRenderer fallback = new DefaultTreeCellRenderer();
    private Icon icon;
    private String prefix;
    private String name;
    private String detail;
    private String flag;
    private Color foreground;
    private Color muted;
    private Color flagColor;
    private Color background;
    private Font font;

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                  boolean leaf, int row, boolean focus) {
        Object user = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
        if (!(user instanceof PluginTrees.Node node) || node.label() == null) {
            Component c = fallback.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (user instanceof PluginTrees.Node node && node.icon() != null) {
                fallback.setIcon(node.icon());
            }
            return c;
        }
        PluginTrees.Label label = node.label();
        icon = node.icon();
        prefix = label.prefix();
        name = label.name();
        detail = label.detail() == null || label.detail().isBlank() ? null : "(" + label.detail() + ")";
        flag = label.flag() == null ? null : label.flag().text();
        font = tree.getFont();
        foreground = selected ? color("Tree.selectionForeground", tree.getForeground()) : tree.getForeground();
        muted = selected ? foreground : MUTED;
        flagColor = label.flag() == null || selected ? muted : flagColor(label.flag().kind());
        background = selected ? color("Tree.selectionBackground", tree.getBackground()) : null;
        setToolTipText(node.tooltip());
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        if (font == null) {
            return new Dimension(0, 0);
        }
        int width = icon == null ? 0 : icon.getIconWidth() + GAP;
        width += textWidth(prefix, font) + textWidth(name, bold()) + textWidth(detail, small())
                + textWidth(flag, italic());
        int height = Math.max(icon == null ? 0 : icon.getIconHeight(), getFontMetrics(font).getHeight());
        return new Dimension(width + 2, height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (background != null) {
                g.setColor(background);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            int x = 0;
            if (icon != null) {
                icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2);
                x += icon.getIconWidth() + GAP;
            }
            int baseline = (getHeight() + getFontMetrics(font).getAscent() - getFontMetrics(font).getDescent()) / 2;
            x = draw(g, prefix, font, muted, x, baseline);
            x = draw(g, name, bold(), foreground, x, baseline);
            x = draw(g, detail, small(), muted, x, baseline);
            draw(g, flag, italic(), flagColor, x, baseline);
        } finally {
            g.dispose();
        }
    }

    private int draw(Graphics2D g, String text, Font f, Color color, int x, int baseline) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        g.setFont(f);
        g.setColor(color);
        g.drawString(text, x, baseline);
        return x + textWidth(text, f);
    }

    private int textWidth(String text, Font f) {
        return text == null || text.isEmpty() ? 0 : getFontMetrics(f).stringWidth(text) + GAP;
    }

    private Font bold() {
        return font.deriveFont(Font.BOLD);
    }

    private Font small() {
        return font.deriveFont(font.getSize2D() - 1f);
    }

    private Font italic() {
        return font.deriveFont(Font.ITALIC, font.getSize2D() - 1f);
    }

    private static Color flagColor(PluginTrees.Flag.Kind kind) {
        return switch (kind) {
            case IN_USE -> new Color(0x2f6fd0);
            case PENDING -> new Color(0xc77800);
            case FAILED -> new Color(0xd03030);
            case HIDDEN, NOT_INSTALLED -> new Color(0x999999);
        };
    }

    private static Color color(String key, Color otherwise) {
        Color c = UIManager.getColor(key);
        return c != null ? c : otherwise;
    }
}
