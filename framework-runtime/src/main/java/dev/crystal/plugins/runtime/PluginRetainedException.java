package dev.crystal.plugins.runtime;

import java.util.List;

/**
 * {@link PluginService#uninstall} refused: something outside the plugins it would remove still holds a fixed
 * reference to one of their implementations. The data is there for the application's own wording; the plugins
 * can be removed on the next start instead ({@link PluginService#uninstallOnNextStart}).
 */
public final class PluginRetainedException extends PluginException {

    private final List<String> plugins;
    private final List<String> holders;

    PluginRetainedException(List<String> plugins, List<String> holders) {
        super("Cannot uninstall " + String.join(", ", plugins) + " now: held by " + String.join(", ", holders));
        this.plugins = List.copyOf(plugins);
        this.holders = List.copyOf(holders);
    }

    /** The plugins the uninstall would have removed, sub-plugins first. */
    public List<String> plugins() {
        return plugins;
    }

    /** Who holds them: {@code plugin 'id'} for a plugin, the class name for an application object. */
    public List<String> holders() {
        return holders;
    }
}
