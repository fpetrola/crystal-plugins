package dev.crystal.plugins.runtime.internal;

import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * The generic PF4J {@code Plugin-Class} of every framework plugin.
 *
 * <p>Authors never subclass {@code org.pf4j.Plugin}; the build writes this class name into the manifest and
 * the runtime instantiates it (the manual path may omit {@code Plugin-Class} altogether). It carries no
 * logic: PF4J calls {@link #start()}/{@link #stop()} in dependency order and it delegates to the plugin's
 * role implementations through {@link PluginScopes}.
 */
public final class RolePlugin extends Plugin {

    private final PluginWrapper wrapper;
    private final PluginScopes scopes;

    RolePlugin(PluginWrapper wrapper, PluginScopes scopes) {
        this.wrapper = wrapper;
        this.scopes = scopes;
    }

    @Override
    public void start() {
        scopes.activate(wrapper);
    }

    @Override
    public void stop() {
        scopes.deactivate(wrapper);
    }
}
