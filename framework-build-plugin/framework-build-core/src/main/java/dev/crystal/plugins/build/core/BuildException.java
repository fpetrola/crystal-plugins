package dev.crystal.plugins.build.core;

/** The plugin cannot be packaged; the message says why and what to do. */
public class BuildException extends RuntimeException {

    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
