package dev.ghostmason.core.ai;

/** A failure talking to an AI provider, with a message fit to show the player. */
public class AiException extends Exception {
	public AiException(String message) {
		super(message);
	}

	public AiException(String message, Throwable cause) {
		super(message, cause);
	}
}
