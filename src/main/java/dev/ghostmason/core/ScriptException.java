package dev.ghostmason.core;

/** The AI's build script could not be read or run. */
public class ScriptException extends Exception {
	public ScriptException(String message) {
		super(message);
	}
}
