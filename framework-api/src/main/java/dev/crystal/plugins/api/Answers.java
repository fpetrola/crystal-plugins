package dev.crystal.plugins.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The keys an extension answers for a role: what the application looks up to find who handles something, such as
 * the file extensions a format reader opens or the machine names it builds. The build writes them into the
 * plugin's metadata, so they can be read, and searched, without loading the plugin, and even before installing it
 * ({@code PluginDescription.answers()}, {@code PluginService.answering}).
 *
 * <pre>{@code
 * @Answers({"tap", "tzx"})
 * public class TapeReader implements SnapshotFile { ... }
 * }</pre>
 *
 * The keys are the application's vocabulary; the framework only stores and matches them (ignoring case). An
 * extension implementing several roles says which one with {@link #role()}, and may repeat the annotation.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Answers.List.class)
public @interface Answers {

    /** The keys. */
    String[] value();

    /** The role they are for; by default every role the extension implements. */
    Class<?> role() default Object.class;

    /** Container of repeated {@link Answers}. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        Answers[] value();
    }
}
