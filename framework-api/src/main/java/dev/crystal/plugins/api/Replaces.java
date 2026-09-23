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
 * <p>Replacement is <strong>reversible</strong> and limited to the roles both implement: the replaced
 * implementation keeps running but is hidden from every view of those roles, and becomes visible again as
 * soon as the replacing one is no longer active. This is the first rule of
 * {@link ConflictResolver#standard()}; an application with its own {@link ConflictResolver} decides for itself.
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
