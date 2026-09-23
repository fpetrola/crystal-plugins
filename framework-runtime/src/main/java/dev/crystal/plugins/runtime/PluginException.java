package dev.crystal.plugins.runtime;

/** Unrecoverable problem while preparing or loading plugins (bad source, integrity failure...). */
public class PluginException extends RuntimeException {

    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
