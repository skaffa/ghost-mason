package dev.ghostmason.core.ai;

/** One turn of a conversation with the AI. Role is "user" or "assistant". */
public record ChatMessage(String role, String content) {
	public static ChatMessage user(String content) {
		return new ChatMessage("user", content);
	}

	public static ChatMessage assistant(String content) {
		return new ChatMessage("assistant", content);
	}
}
