package dev.ghostmason.core.ai;

/** The player cancelled the request while the AI was working. */
public class CancelledException extends AiException {
	public CancelledException() {
		super("Cancelled.");
	}
}
