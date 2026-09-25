package dev.crystal.plugins.build.processor;

/**
 * One {@code @Offers} on an extension.
 *
 * @param text the action, as text a person reads
 * @param icon an icon name the application understands, or empty
 */
record OfferModel(String text, String icon) {
}
