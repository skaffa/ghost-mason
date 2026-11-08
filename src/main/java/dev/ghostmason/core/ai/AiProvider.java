package dev.ghostmason.core.ai;

import java.util.List;

/** A chat-completion backend. Implementations block, so call them off the game thread. */
public interface AiProvider {
	/** Human-readable name, e.g. "Claude (claude-opus-5-5)". */
	String displayName();

	/**
	 * Sends the conversation and returns the model's full text reply.
	 *
	 * @param wantThinking ask the provider for readable reasoning, where it offers that
	 * @param listener     receives progress while the answer is generated
	 */
	String complete(String system, List<ChatMessage> messages, boolean wantThinking, AiListener listener) throws AiException;

	default String complete(String system, List<ChatMessage> messages) throws AiException {
		return complete(system, messages, false, AiListener.NONE);
	}
}
