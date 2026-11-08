package dev.ghostmason.core.ai;

import com.google.gson.JsonObject;

/** Reads token counts from Anthropic-style stream events (used by the API and Claude Code). */
final class Usage {
	private Usage() {
	}

	/**
	 * message_start carries the input count in message.usage; message_delta carries the
	 * running output count in usage. Cache reads and writes count as input too.
	 */
	static void report(JsonObject event, AiListener listener) {
		String type = event.has("type") ? event.get("type").getAsString() : "";
		JsonObject usage = null;
		if (type.equals("message_start") && event.has("message") && event.getAsJsonObject("message").has("usage")) {
			usage = event.getAsJsonObject("message").getAsJsonObject("usage");
		} else if (type.equals("message_delta") && event.has("usage") && event.get("usage").isJsonObject()) {
			usage = event.getAsJsonObject("usage");
		}
		if (usage == null) {
			return;
		}
		long input = -1;
		if (usage.has("input_tokens")) {
			input = num(usage, "input_tokens") + Math.max(0, num(usage, "cache_read_input_tokens"))
					+ Math.max(0, num(usage, "cache_creation_input_tokens"));
		}
		long output = num(usage, "output_tokens");
		listener.usage(input, output);
	}

	private static long num(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : -1;
	}
}
