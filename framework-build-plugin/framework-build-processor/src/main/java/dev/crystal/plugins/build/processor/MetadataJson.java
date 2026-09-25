package dev.crystal.plugins.build.processor;

import java.util.Collection;
import java.util.Iterator;

/**
 * Writes the source-derived part of {@code plugin-metadata.json}.
 *
 * <p>Hand-rolled on purpose (the processor has no dependencies) and deterministic: same input, same bytes.
 * Build-derived fields ({@code id}, {@code version}, {@code apiVersion}, {@code dependencies}...) are merged
 * in later by the build-tool integration, which knows the project coordinates.
 */
final class MetadataJson {

    private MetadataJson() {
    }

    static String write(Collection<ExtensionModel> extensions, Collection<String> definedRoles) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"format\": ").append(Contract.METADATA_FORMAT).append(",\n");
        out.append("  \"extensions\": [");
        Iterator<ExtensionModel> it = extensions.iterator();
        while (it.hasNext()) {
            ExtensionModel e = it.next();
            out.append("\n    {\n");
            out.append("      \"class\": ").append(quote(e.className())).append(",\n");
            out.append("      \"roles\": ").append(array(e.roles())).append(",\n");
            out.append("      \"replaces\": ").append(array(e.replaces())).append(",\n");
            out.append("      \"needs\": ").append(array(e.needs())).append(",\n");
            out.append("      \"lifecycle\": ").append(e.lifecycle());
            if (!e.answers().isEmpty()) {
                out.append(",\n      \"answers\": {");
                Iterator<java.util.Map.Entry<String, java.util.List<String>>> a = e.answers().entrySet().iterator();
                while (a.hasNext()) {
                    var entry = a.next();
                    out.append("\n        ").append(quote(entry.getKey())).append(": ").append(array(entry.getValue()));
                    if (a.hasNext()) {
                        out.append(',');
                    }
                }
                out.append("\n      }");
            }
            if (!e.offers().isEmpty()) {
                out.append(",\n      \"offers\": [");
                Iterator<OfferModel> o = e.offers().iterator();
                while (o.hasNext()) {
                    OfferModel offer = o.next();
                    out.append("\n        {\"text\": ").append(quote(offer.text())).append(", \"icon\": ")
                            .append(quote(offer.icon())).append('}');
                    if (o.hasNext()) {
                        out.append(',');
                    }
                }
                out.append("\n      ]");
            }
            out.append("\n");
            out.append("    }");
            if (it.hasNext()) {
                out.append(',');
            }
        }
        out.append(extensions.isEmpty() ? "],\n" : "\n  ],\n");
        out.append("  \"definesRoles\": ").append(array(definedRoles)).append("\n");
        out.append("}\n");
        return out.toString();
    }

    private static String array(Collection<String> values) {
        StringBuilder out = new StringBuilder("[");
        Iterator<String> it = values.iterator();
        while (it.hasNext()) {
            out.append(quote(it.next()));
            if (it.hasNext()) {
                out.append(", ");
            }
        }
        return out.append(']').toString();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
