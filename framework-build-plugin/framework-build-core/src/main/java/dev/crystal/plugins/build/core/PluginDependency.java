package dev.crystal.plugins.build.core;

import java.util.List;
import java.util.Locale;

/**
 * A plugin this plugin needs at runtime.
 *
 * @param id      the other plugin's {@code Plugin-Id}
 * @param version the other plugin's {@code Plugin-Version} this one was compiled against, or {@code null} when
 *                it was not on the compile classpath (only possible for {@code @Needs})
 * @param source  why the dependency exists
 * @param types   for {@link Source#BYTECODE}: the other plugin's types this one references (sorted)
 */
public record PluginDependency(String id, String version, Source source, List<String> types) {

    public PluginDependency {
        types = List.copyOf(types);
    }

    public enum Source {
        /** Found in the constant pool of the compiled classes. */
        BYTECODE,
        /** Declared with {@code @Needs} (reflection, invisible to bytecode analysis). */
        NEEDS;

        String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** PF4J {@code Plugin-Dependencies} item: {@code id@version} (exact) or bare {@code id} (any version). */
    String pf4j() {
        return version == null ? id : id + "@" + version;
    }
}
