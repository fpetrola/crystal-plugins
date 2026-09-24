package dev.crystal.plugins.swing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import org.junit.jupiter.api.Test;

class NodeRendererTest {

    private static DefaultMutableTreeNode node(String name, String detail, PluginTrees.Flag flag) {
        return new DefaultMutableTreeNode(new PluginTrees.Node(name, name, null, PluginIcons.extension())
                .label(null, name, detail, flag));
    }

    /** Paints what the renderer returns for {@code node}, as a look and feel does: no layout in between. */
    private static int[] paint(NodeRenderer renderer, JTree tree, DefaultMutableTreeNode node) {
        Component c = renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false);
        c.setSize(400, 20);
        BufferedImage image = new BufferedImage(400, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 400, 20);
        c.paint(g);
        g.dispose();
        return image.getRGB(0, 0, 400, 20, null, 0, 400);
    }

    @Test
    void aRowDrawsTheSameWhateverTheRendererDrewBefore() {
        JTree tree = new JTree();
        DefaultMutableTreeNode opus = node("Opus Discovery", "device-opus · 1.0.0", null);
        DefaultMutableTreeNode group = node("device", "44", new PluginTrees.Flag("in use", PluginTrees.Flag.Kind.IN_USE));

        int[] fresh = paint(new NodeRenderer(), tree, opus);
        NodeRenderer reused = new NodeRenderer();
        paint(reused, tree, group);
        assertArrayEquals(fresh, paint(reused, tree, opus),
                "a renderer reused for another row, without a layout in between, must not keep that row's text");
        assertTrue(java.util.Arrays.stream(fresh).anyMatch(p -> p != 0xFFFFFFFF), "and it draws something");
    }
}
