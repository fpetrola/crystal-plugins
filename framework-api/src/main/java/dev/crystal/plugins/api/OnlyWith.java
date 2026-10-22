package dev.crystal.plugins.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An extension that is only there with these other plugins: the plugin works without them, and leaves this
 * extension out while one is missing.
 *
 * <p>What the extension references of them is not a dependency of the plugin. The build writes them as optional
 * ({@code id?} in {@code Plugin-Dependencies}), installing the plugin does not install them, and the runtime offers
 * the extension only while they are loaded, reloading the plugin when one of them comes or goes. Only the extension
 * and the classes nested in it may reference their types: the build fails if another class does.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OnlyWith {
    /** Ids of the plugins without which this extension is left out. */
    String[] value();
}
