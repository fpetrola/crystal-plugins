package dev.crystal.plugins.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this implementation substitutes another implementation of the same role.
 *
 * <p>The value is an <em>implementation id</em>: either a plugin id ({@code "csv-exporter"}), meaning every
 * implementation of the shared role(s) contributed by that plugin, or a qualified id
 * {@code "plugin-id:fully.qualified.ClassName"} to target a single class.
 *
 * <p>Replacement is <strong>reversible</strong>: the replaced implementation is hidden, not removed. If the
 * replacing plugin is stopped or unloaded, the replaced one becomes visible again. The default
 * conflict resolution (milestone 6) gives {@code @Replaces} precedence over version ordering.
 *
 * <p>This is one of the few manual annotations: it records a real decision the build cannot infer.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Replaces {

    /** Implementation id(s) replaced by the annotated class. */
    String[] value();
}
