package dev.crystal.plugins.build.processor;

/**
 * Names that make up the public metadata contract (see {@code docs/metadata-format.md}).
 *
 * <p>Kept as strings rather than class literals: the processor must work against whatever framework-api
 * version the author compiles with, and must not drag the API onto the processor path.
 */
public final class Contract {

    public static final String ROLE_INTERFACE = "dev.crystal.plugins.api.RoleInterface";
    public static final String REPLACES = "dev.crystal.plugins.api.Replaces";
    public static final String NEEDS = "dev.crystal.plugins.api.Needs";
    public static final String HAS_LIFECYCLE = "dev.crystal.plugins.api.HasLifecycle";

    public static final String JAKARTA_INJECT = "jakarta.inject.Inject";
    public static final String JAVAX_INJECT = "javax.inject.Inject";

    /** PF4J's extension index; one class name per line, {@code #} starts a comment. */
    public static final String EXTENSIONS_INDEX = "META-INF/extensions.idx";
    /** Framework metadata, JSON. */
    public static final String METADATA = "META-INF/plugin-metadata.json";
    public static final String SERVICES_DIR = "META-INF/services/";

    /** Version of the plugin-metadata.json layout written by this processor. */
    public static final int METADATA_FORMAT = 1;

    /** Generic PF4J plugin class shipped by framework-runtime; it delegates to the role implementations. */
    public static final String GENERIC_PLUGIN_CLASS = "dev.crystal.plugins.runtime.internal.RolePlugin";

    private Contract() {
    }
}
