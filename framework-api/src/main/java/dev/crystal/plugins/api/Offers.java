package dev.crystal.plugins.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The actions an extension offers to the person: written into the plugin's metadata, so they can be listed
 * before installing the plugin.
 *
 * <pre>{@code
 * @Offers("Browse the game catalogue")
 * public class CatalogueBrowser implements Panel { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Offers.List.class)
public @interface Offers {

    /** The action, as text a person reads. */
    String value();

    /** An icon name the application understands. */
    String icon() default "";

    /** Container of repeated {@link Offers}. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        Offers[] value();
    }
}
