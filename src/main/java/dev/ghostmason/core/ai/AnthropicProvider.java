package dev.ghostmason.core.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Claude via the Anthropic Messages API (raw HTTP, streamed).
 *
 * <p>The official Java SDK is not used because it would have to be bundled into the
 * mod jar together with its OkHttp/Jackson/Kotlin dependencies, which tends to clash
 * with other mods. java.net.http plus Minecraft's bundled Gson covers what we need.
 */
public final class AnthropicProvider implements AiProvider {
	private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
	private static final String API_VERSION = "2026-10-21";
	/** Server-side refusal fallback ("fallbacks": "default"). */
	private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

	private final String apiKey;
	private final String model;
	private final int maxTokens;
	private final String effort;

	public AnthropicProvider(String apiKey, String model, int maxTokens, String effort) {
		this.apiKey = apiKey;
		this.model = model;
		this.maxTokens = maxTokens;
		this.effort = effort;
	}

	@Override
	public String displayName() {
		return "Claude (" + model + ")";
	}

	/** Models that accept {@code "fallbacks": "default"}. */
	static boolean supportsDefaultFallbacks(String model) {
		return model.startsWith("claude-opus-5")
				|| model.startsWith("claude-fable-5")
				|| model.equals("claude-sonnet-5-5");
	}

	/** Current-generation models that take adaptive thinking with a display setting. */
	static boolean supportsThinkingDisplay(String model) {
		return model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")
				|| model.startsWith("claude-sonnet-5") || model.startsWith("claude-haiku-5");
	}

	@Override
	public String complete(String system, List<ChatMessage> messages, boolean wantThinking, AiListener listener) throws AiException {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("stream", true);
		body.addProperty("system", system);

		JsonArray msgs = new JsonArray();
		for (ChatMessage m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}
		body.add("messages", msgs);

		if (supportsThinkingDisplay(model)) {
			JsonObject thinking = new JsonObject();
			thinking.addProperty("type", "adaptive");
			// "summarized" returns readable reasoning; "omitted" (the default) keeps it hidden.
			thinking.addProperty("display", wantThinking ? "summarized" : "omitted");
			body.add("thinking", thinking);
		}
		if (effort != null && !effort.isBlank()) {
			JsonObject outputConfig = new JsonObject();
			outputConfig.addProperty("effort", effort);
			body.add("output_config", outputConfig);
		}

		boolean fallbacks = supportsDefaultFallbacks(model);
		if (fallbacks) {
			body.addProperty("fallbacks", "default");
		}

		HttpRequest.Builder request = HttpRequest.newBuilder(ENDPOINT)
				.timeout(Duration.ofMinutes(15))
				.header("content-type", "application/json")
				.header("x-api-key", apiKey)
				.header("anthropic-version", API_VERSION)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		if (fallbacks) {
			request.header("anthropic-beta", FALLBACK_BETA);
		}

		HttpResponse<Stream<String>> response = Http.sendStreaming(request.build(), "Claude");
		if (response.statusCode() != 200) {
			throw new AiException("Claude API error " + response.statusCode() + ": " + Http.errorMessage(Http.drain(response)));
		}

		StringBuilder text = new StringBuilder();
		String stopReason = "";
		try (Stream<String> lines = response.body()) {
			Iterator<String> it = lines.iterator();
			while (it.hasNext()) {
				String line = it.next();
				if (listener.cancelled()) {
					throw new CancelledException(); // closing the stream ends the request
				}
				if (!line.startsWith("data:")) {
					continue;
				}
				JsonObject event;
				try {
					event = JsonParser.parseString(line.substring(5).trim()).getAsJsonObject();
				} catch (RuntimeException e) {
					continue;
				}
				String type = str(event, "type");
				Usage.report(event, listener);
				switch (type) {
					case "content_block_start" -> {
						String blockType = str(event.getAsJsonObject("content_block"), "type");
						if (blockType.equals("thinking")) {
							listener.phase(AiListener.THINKING);
						} else if (blockType.equals("text")) {
							listener.phase(AiListener.WRITING);
						}
					}
					case "content_block_delta" -> {
						JsonObject delta = event.getAsJsonObject("delta");
						switch (str(delta, "type")) {
							case "text_delta" -> {
								String t = str(delta, "text");
								text.append(t);
								listener.text(t);
							}
							case "thinking_delta" -> {
								String t = str(delta, "thinking");
								if (!t.isEmpty()) {
									listener.thinking(t);
								}
							}
							default -> {
								// signatures and other deltas are not needed
							}
						}
					}
					case "message_delta" -> {
						JsonObject delta = event.getAsJsonObject("delta");
						if (delta != null && delta.has("stop_reason") && !delta.get("stop_reason").isJsonNull()) {
							stopReason = delta.get("stop_reason").getAsString();
						}
					}
					case "error" -> {
						JsonObject err = event.getAsJsonObject("error");
						throw new AiException("Claude API error: " + (err == null ? line : str(err, "message")));
					}
					default -> {
						// message_start, ping, content_block_stop, message_stop
					}
				}
			}
		} catch (java.io.UncheckedIOException e) {
			throw new AiException("Connection to Claude dropped: " + e.getMessage(), e);
		}

		// Check stop_reason before using the text: a refusal can arrive with partial or no text.
		if (stopReason.equals("refusal")) {
			throw new AiException("Claude declined this request. Try rephrasing it.");
		}
		if (stopReason.equals("max_tokens")) {
			throw new AiException("Claude's answer was cut off at " + maxTokens + " tokens. Raise the limit with /gm config maxtokens <n>, or use a smaller selection.");
		}
		if (text.isEmpty()) {
			throw new AiException("Claude returned no text (stop_reason: " + stopReason + ").");
		}
		return text.toString();
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}
}
