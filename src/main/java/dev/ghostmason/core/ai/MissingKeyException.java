package dev.ghostmason.core.ai;

/** No API key is configured for the selected provider. */
public class MissingKeyException extends AiException {
	private final String provider;

	public MissingKeyException(String provider, String message) {
		super(message);
		this.provider = provider;
	}

	/** "claude" or "deepseek". */
	public String provider() {
		return provider;
	}
}
