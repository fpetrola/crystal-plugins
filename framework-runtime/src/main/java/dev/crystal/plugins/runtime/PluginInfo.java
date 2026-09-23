package dev.crystal.plugins.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view of a loaded plugin, in framework terms (no PF4J types leak out): enough for an application's
 * plugin panel without opening any jar.
 *
 * @param id           plugin id
 * @param version      plugin version
 * @param status       current status
 * @param failure      why the plugin failed, when {@code status == FAILED}
 * @param extensions   its extensions: the classes it contributes and the roles each implements
 * @param definesRoles the role interfaces it defines, for sub-plugins to implement
 * @param dependencies the plugins it requires, as {@code id} or {@code id@version}
 * @param apiVersion   the framework-api version it was built against, if its manifest says
 */
public record PluginInfo(String id, String version, Status status, Optional<Throwable> failure,
                         List<ExtensionInfo> extensions, List<String> definesRoles, List<String> dependencies,
                         Optional<String> apiVersion) {

    public PluginInfo {
        extensions = List.copyOf(extensions);
        definesRoles = List.copyOf(definesRoles);
        dependencies = List.copyOf(dependencies);
    }

    /** Only identity and state. */
    public PluginInfo(String id, String version, Status status, Optional<Throwable> failure) {
        this(id, version, status, failure, List.of(), List.of(), List.of(), Optional.empty());
    }

    public enum Status {
        /** Loaded and dependencies resolved, not started. */
        RESOLVED,
        /** Active: its role implementations are visible. */
        STARTED,
        STOPPED,
        FAILED,
        DISABLED,
        /** Removed by {@link PluginService#uninstall}: stopped, unloaded and no longer installed. */
        UNLOADED
    }

    /**
     * One class a plugin contributes.
     *
     * @param className binary class name
     * @param roles     the role interfaces it implements
     * @param replaces  the values of its {@code @Replaces}
     */
    public record ExtensionInfo(String className, List<String> roles, List<String> replaces) {

        public ExtensionInfo {
            roles = List.copyOf(roles);
            replaces = List.copyOf(replaces);
        }
    }
}
