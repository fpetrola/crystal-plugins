package dev.crystal.plugins.api;

/**
 * Opt-in lifecycle for role implementations.
 *
 * <p>Implement it only when the implementation owns resources (threads, sockets, native handles). The
 * runtime calls {@link #onStart()} after every implementation of the plugin has been constructed and
 * injected, and {@link #onStop()} before the plugin's classes are released, in reverse order.
 *
 * <p>This deliberately replaces "extends {@code org.pf4j.Plugin}": authors never extend a framework class.
 */
public interface HasLifecycle {

    /** Called once the plugin is active. Throwing aborts the plugin start. */
    default void onStart() throws Exception {
    }

    /** Called before the plugin is deactivated. Must release everything acquired in {@link #onStart()}. */
    default void onStop() throws Exception {
    }
}
