package dev.crystal.plugins.api;

/**
 * One action an extension offers ({@link Offers}).
 *
 * @param extension binary class name of the extension that offers it
 * @param text      the action, as text a person reads
 * @param icon      an icon name the application understands, or empty
 */
public record Offer(String extension, String text, String icon) {
}
