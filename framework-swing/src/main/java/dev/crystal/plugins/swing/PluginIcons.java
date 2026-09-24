package dev.crystal.plugins.swing;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.Icon;
import javax.swing.ImageIcon;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

/**
 * The panel's icons: OpenMoji emoji (CC BY-SA 4.0, https://openmoji.org), SVG files under
 * {@code dev/crystal/plugins/swing/icons/} rendered with JSVG. A plugin's icon carries a small badge telling
 * where it comes from.
 */
public final class PluginIcons {

    public static final int SIZE = 18;
    private static final int BADGE = 11;

    private static final String INSTALL = "1F4E5";      // inbox tray
    private static final String REMOVE = "1F5D1";       // wastebasket
    private static final String CHECK = "1F504";        // arrows
    private static final String PLUGIN = "1F9E9";       // puzzle piece
    private static final String SUB_PLUGIN = "1F50C";   // plug
    private static final String FAILED = "274C";        // cross
    private static final String PENDING = "23F3";       // hourglass
    private static final String BUNDLED = "1F4E6";      // package
    private static final String LOCAL = "1F4C1";        // folder
    private static final String CATALOG = "1F310";      // globe
    private static final String APPLICATION = "1F4BB";  // computer
    private static final String ROLE = "1F3AD";         // performing arts
    private static final String CLASS = "2699";         // gear
    private static final String REQUIRES = "1F517";     // link
    private static final String PLUS = "2795";

    private static final SVGLoader LOADER = new SVGLoader();
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    /** Where a plugin comes from, shown as a badge on its icon. */
    public enum Origin {
        /** Inside the application (its default plugins). */
        BUNDLED,
        /** A folder on this machine. */
        LOCAL,
        /** The catalog, typically remote. */
        CATALOG,
        /** Not a plugin: the application's own implementations. */
        APPLICATION
    }

    private PluginIcons() {
    }

    public static Icon install() {
        return icon(INSTALL, null, false);
    }

    public static Icon remove() {
        return icon(REMOVE, null, false);
    }

    public static Icon check() {
        return icon(CHECK, null, false);
    }

    /**
     * A plugin: a puzzle piece, a plug when it depends on other plugins (a sub-plugin), a cross when it failed
     * and an hourglass when it goes away on the next start; badged with where it comes from.
     */
    public static Icon plugin(boolean sub, boolean failed, boolean pending, Origin origin) {
        String base = failed ? FAILED : pending ? PENDING : sub ? SUB_PLUGIN : PLUGIN;
        return icon(base, badge(origin), false);
    }

    /** A role interface: the masks; with a puzzle badge when a plugin defines it (a point for sub-plugins). */
    public static Icon role(boolean definedByPlugin) {
        return icon(ROLE, definedByPlugin ? PLUGIN : null, false);
    }

    /** "implements Role". */
    public static Icon implementsRole() {
        return icon(ROLE, null, false);
    }

    /** "defines Role". */
    public static Icon definesRole() {
        return icon(ROLE, PLUS, false);
    }

    /** A class implementing roles. */
    public static Icon extension() {
        return icon(CLASS, null, false);
    }

    /** An implementation under a role: faded when hidden or not installed, badged with where it comes from. */
    public static Icon implementation(boolean visible, boolean installed, Origin origin) {
        return icon(CLASS, badge(origin), !visible || !installed);
    }

    /** "requires ...". */
    public static Icon requires() {
        return icon(REQUIRES, null, false);
    }

    private static String badge(Origin origin) {
        if (origin == null) {
            return null;
        }
        return switch (origin) {
            case BUNDLED -> BUNDLED;
            case LOCAL -> LOCAL;
            case CATALOG -> CATALOG;
            case APPLICATION -> APPLICATION;
        };
    }

    private static Icon icon(String base, String badge, boolean faded) {
        return CACHE.computeIfAbsent(base + "/" + badge + "/" + faded, k -> {
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                if (faded) {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
                }
                paint(g, base, 0, 0, SIZE);
                if (badge != null) {
                    g.setComposite(AlphaComposite.SrcOver);
                    paint(g, badge, SIZE - BADGE, SIZE - BADGE, BADGE);
                }
            } finally {
                g.dispose();
            }
            return new ImageIcon(image);
        });
    }

    private static void paint(Graphics2D g, String name, int x, int y, int size) {
        URL url = PluginIcons.class.getResource("icons/" + name + ".svg");
        SVGDocument document = url == null ? null : LOADER.load(url);
        if (document == null) {
            return; // a missing icon leaves the space empty rather than failing the panel
        }
        document.render(null, g, new ViewBox(x, y, size, size));
    }
}
