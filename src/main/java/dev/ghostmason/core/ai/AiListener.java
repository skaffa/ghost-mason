package dev.ghostmason.core.ai;

/**
 * Live progress from a provider while it works. Called on the AI thread, so implementations
 * must hand anything that touches the game over to the game thread.
 */
public interface AiListener {
	AiListener NONE = new AiListener() {
	};

	/** The model started a new stage: {@link #THINKING} or {@link #WRITING}. */
	default void phase(String phase) {
	}

	/** A piece of the model's visible reasoning (only from providers that expose it). */
	default void thinking(String delta) {
	}

	/** A piece of the answer text as it is generated. */
	default void text(String delta) {
	}

	/**
	 * Token counts reported by the provider, possibly several times as they grow.
	 * Output includes reasoning tokens.
	 */
	default void usage(long inputTokens, long outputTokens) {
	}

	/** Providers check this while streaming and stop with {@link CancelledException} when true. */
	default boolean cancelled() {
		return false;
	}

	String THINKING = "thinking";
	String WRITING = "writing";
}
