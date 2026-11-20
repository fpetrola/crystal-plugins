package dev.crystal.plugins.api;

/**
 * One action an extension offers ({@link Offers}).
 *
 * @param extension binary class name of the extension that offers it
 * @param text      the action, as text a person reads
 * @param icon      an icon name the application understands, or empty
 * @param roles     the role interfaces the extension implements (class names): where the application puts it
 * @param description what it is, in a line a person reads, or empty
 * @param known       how a catalogue outside the application knows it, as {@code catalogue:id}, or empty
 */
public record Offer(String extension, String text, String icon, java.util.List<String> roles, String description,
                    String known) {
    public Offer {
        roles = java.util.List.copyOf(roles);
    }

    public Offer(String extension, String text, String icon, java.util.List<String> roles) {
        this(extension, text, icon, roles, "", "");
    }

    public Offer(String extension, String text, String icon) {
        this(extension, text, icon, java.util.List.of());
    }

    /** Whether the extension offering it implements {@code role}. */
    public boolean is(Class<?> role) {
        return roles.contains(role.getName());
    }
}
