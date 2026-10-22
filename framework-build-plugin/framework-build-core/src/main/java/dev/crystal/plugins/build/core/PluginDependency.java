package dev.crystal.plugins.build.core;

import java.util.List;

/**
 * A plugin this plugin needs at runtime.
 *
 * @param id       the other plugin's {@code Plugin-Id}
 * @param version  the other plugin's {@code Plugin-Version} this one was compiled against, or {@code null} when
 *                 it was not on the compile classpath (only possible for {@code @Needs} and {@code @OnlyWith})
 * @param source   why the dependency exists
 * @param types    for {@link Source#BYTECODE}: the other plugin's types this one references (sorted)
 * @param optional only the extensions that declare it with {@code @OnlyWith} need it: the plugin works without it
 */
public record PluginDependency(String id, String version, Source source, List<String> types, boolean optional) {

    public PluginDependency {
        types = List.copyOf(types);
    }

    /** A dependency the whole plugin needs. */
    public PluginDependency(String id, String version, Source source, List<String> types) {
        this(id, version, source, types, false);
    }

    public enum Source {
        /** Found in the constant pool of the compiled classes. */
        BYTECODE("bytecode"),
        /** Declared with {@code @Needs} (reflection, invisible to bytecode analysis). */
        NEEDS("needs"),
        /** Declared with {@code @OnlyWith} and not in the bytecode: the extension reaches it some other way. */
        ONLY_WITH("onlyWith");

        private final String json;

        Source(String json) {
            this.json = json;
        }

        String json() {
            return json;
        }
    }

    /**
     * PF4J {@code Plugin-Dependencies} item: {@code id@version} (exact) or bare {@code id} (any version), with a
     * {@code ?} after the id when it is optional.
     */
    String pf4j() {
        String named = optional ? id + "?" : id;
        return version == null ? named : named + "@" + version;
    }
}
