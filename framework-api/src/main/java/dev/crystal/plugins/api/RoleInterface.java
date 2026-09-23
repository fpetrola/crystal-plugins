package dev.crystal.plugins.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an extension point (a <em>role</em>).
 *
 * <p>Implementing a {@code @RoleInterface} interface <strong>is</strong> the declaration that a class is
 * pluggable: there is no {@code @Plugin}, no {@code @Extension} and no central registry. Whoever owns the
 * interface (the host application, or a plugin that wants sub-plugins) defines the role; whoever implements
 * it provides the role.
 *
 * <pre>{@code
 * @RoleInterface
 * public interface ReportExporter {
 *     String format();
 *     byte[] export(Report report);
 * }
 *
 * // In a plugin: nothing else to write.
 * public class CsvExporter implements ReportExporter { ... }
 * }</pre>
 *
 * <p>The annotation is looked up on every interface a class implements, directly or through
 * super-interfaces, so a role may be refined by sub-interfaces without repeating the annotation on them.
 *
 * <p>Retention is {@code RUNTIME} on purpose: the build plugin finds roles in compiled API jars (it never
 * sees their source), and the runtime re-checks them when it binds implementations.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RoleInterface {
}
