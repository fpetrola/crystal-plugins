package dev.crystal.plugins.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Escape hatch: declares a dependency on another plugin that is invisible to bytecode analysis
 * (typically because it is only reached through reflection or {@code Class.forName}).
 *
 * <p>Normally unnecessary: the build plugin derives {@code Plugin-Dependencies} from the constant pool of
 * the compiled classes. Ids listed here are added to that set.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Needs {

    /** Ids of the plugins this class needs at runtime. */
    String[] value();
}
