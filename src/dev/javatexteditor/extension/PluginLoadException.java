package dev.javatexteditor.extension;

public class PluginLoadException extends Exception {
    public PluginLoadException(String message) { super(message); }
    public PluginLoadException(String message, Throwable cause) { super(message, cause); }
}
