package dev.crystal.plugins.build.core;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Completes the processor's {@code plugin-metadata.json} with what only the build tool knows.
 *
 * <p>The processor writes {@code format}, {@code extensions} and {@code definesRoles}; this class adds
 * {@code id}, {@code version}, {@code apiVersion}, {@code roleApis} and {@code dependencies}, keeping a fixed
 * key order so the file is stable across builds. See {@code docs/metadata-format.md}.
 */
final class PluginMetadata {

    /** Where a role interface was compiled from. */
    record RoleApi(String role, String artifact, String version) {
    }

    private final JsonObject source;

    private PluginMetadata(JsonObject source) {
        this.source = source;
    }

    static PluginMetadata parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        int format = object.get("format").getAsInt();
        if (format != 1) {
            throw new IllegalArgumentException("Unsupported plugin-metadata.json format " + format);
        }
        return new PluginMetadata(object);
    }

    /** Binary names of the classes the processor indexed. */
    Set<String> extensionClasses() {
        Set<String> classes = new TreeSet<>();
        for (JsonElement e : source.getAsJsonArray("extensions")) {
            classes.add(e.getAsJsonObject().get("class").getAsString());
        }
        return classes;
    }

    /** Every role implemented by some extension. */
    Set<String> implementedRoles() {
        Set<String> roles = new TreeSet<>();
        for (JsonElement e : source.getAsJsonArray("extensions")) {
            e.getAsJsonObject().getAsJsonArray("roles").forEach(r -> roles.add(r.getAsString()));
        }
        return roles;
    }

    Set<String> definedRoles() {
        Set<String> roles = new TreeSet<>();
        JsonArray defined = source.getAsJsonArray("definesRoles");
        if (defined != null) {
            defined.forEach(r -> roles.add(r.getAsString()));
        }
        return roles;
    }

    /** Union of {@code @Needs} over all extensions. */
    Set<String> needs() {
        Set<String> needs = new TreeSet<>();
        for (JsonElement e : source.getAsJsonArray("extensions")) {
            JsonArray array = e.getAsJsonObject().getAsJsonArray("needs");
            if (array != null) {
                array.forEach(n -> needs.add(n.getAsString()));
            }
        }
        return needs;
    }

    String complete(String id, String version, String apiVersion, List<RoleApi> roleApis,
                    List<PluginDependency> dependencies) {
        JsonObject out = new JsonObject();
        out.addProperty("format", 1);
        out.addProperty("id", id);
        out.addProperty("version", version);
        out.addProperty("apiVersion", apiVersion);
        out.add("extensions", source.get("extensions"));
        out.add("definesRoles", source.has("definesRoles") ? source.get("definesRoles") : new JsonArray());

        JsonArray apis = new JsonArray();
        for (RoleApi api : roleApis) {
            JsonObject o = new JsonObject();
            o.addProperty("role", api.role());
            o.addProperty("artifact", api.artifact());
            o.addProperty("version", api.version());
            apis.add(o);
        }
        out.add("roleApis", apis);

        JsonArray deps = new JsonArray();
        for (PluginDependency dependency : dependencies) {
            JsonObject o = new JsonObject();
            o.addProperty("id", dependency.id());
            if (dependency.version() != null) {
                o.addProperty("version", dependency.version());
            }
            o.addProperty("source", dependency.source().json());
            if (!dependency.types().isEmpty()) {
                JsonArray types = new JsonArray();
                dependency.types().forEach(types::add);
                o.add("types", types);
            }
            deps.add(o);
        }
        out.add("dependencies", deps);

        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out) + "\n";
    }

    /** PF4J {@code Plugin-Dependencies} syntax: {@code id[@version]}, comma separated. */
    static String toPf4jDependencies(List<PluginDependency> dependencies) {
        StringBuilder out = new StringBuilder();
        for (PluginDependency d : dependencies) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(d.pf4j());
        }
        return out.toString();
    }
}
