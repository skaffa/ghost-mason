package dev.ghostmason.core.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/** DeepSeek via its OpenAI-style chat completions endpoint, streamed. */
public final class DeepSeekProvider implements AiProvider {
	private final String apiKey;
	private final String model;
	private final URI endpoint;
	private final int maxTokens;

	/** "off", "low", "high", "max", or blank for DeepSeek's default. */
	private final String effort;

	public DeepSeekProvider(String apiKey, String model, String baseUrl, int maxTokens, String effort) {
		this.effort = effort;
		this.apiKey = apiKey;
		this.model = model;
		String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.endpoint = URI.create(base + "/chat/completions");
		this.maxTokens = maxTokens;
	}

	@Override
	public String displayName() {
		return "DeepSeek (" + model + ")";
	}

	/**
	 * Sends the request, adapting to what the model accepts: without JSON mode if that is
	 * rejected, and with the model's own maximum if the configured limit is higher than it allows.
	 * The configured limit is never exceeded. Each adjustment is tried once.
	 */
	@Override
	public String complete(String system, List<ChatMessage> messages, boolean wantThinking, AiListener listener) throws AiException {
		boolean jsonMode = !model.contains("reasoner");
		int tokens = maxTokens;
		boolean lowered = false;
		while (true) {
			try {
				return run(system, messages, listener, jsonMode, tokens);
			} catch (JsonModeRejected e) {
				if (!jsonMode) {
					throw e;
				}
				jsonMode = false; // the prompt already asks for JSON
			} catch (LimitTooHigh e) {
				if (lowered || e.maximum <= 0 || e.maximum >= tokens) {
					throw new AiException("DeepSeek API error: " + e.getMessage());
				}
				lowered = true;
				tokens = e.maximum;
			} catch (CutOff e) {
				throw new AiException("DeepSeek's answer was cut off at " + tokens + " tokens (its reasoning counts too). "
						+ "Raise the limit with /gm config maxtokens <n>, or use a smaller selection.");
			}
		}
	}

	private static final class JsonModeRejected extends AiException {
		JsonModeRejected(String message) {
			super(message);
		}
	}

	private static final class LimitTooHigh extends AiException {
		final int maximum;

		LimitTooHigh(String message, int maximum) {
			super(message);
			this.maximum = maximum;
		}
	}

	private static final class CutOff extends AiException {
		CutOff() {
			super("cut off");
		}
	}

	/** The largest number in an error such as "max_tokens must be in [1, 8192]". */
	private static int largestNumber(String message) {
		int best = 0;
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{3,7}").matcher(message);
		while (m.find()) {
			best = Math.max(best, Integer.parseInt(m.group()));
		}
		return best;
	}

	private String run(String system, List<ChatMessage> messages, AiListener listener, boolean jsonMode, int tokens) throws AiException {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", tokens);
		body.addProperty("stream", true);
		// Thinking effort (DeepSeek V4): "off" disables reasoning, otherwise low / high / max.
		if (!effort.isBlank()) {
			JsonObject thinking = new JsonObject();
			if (effort.equalsIgnoreCase("off")) {
				thinking.addProperty("type", "disabled");
			} else {
				thinking.addProperty("type", "enabled");
				body.addProperty("reasoning_effort", effort.toLowerCase(java.util.Locale.ROOT));
			}
			body.add("thinking", thinking);
		}
		JsonObject streamOptions = new JsonObject();
		streamOptions.addProperty("include_usage", true); // final chunk carries the token counts
		body.add("stream_options", streamOptions);

		JsonArray msgs = new JsonArray();
		JsonObject sys = new JsonObject();
		sys.addProperty("role", "system");
		sys.addProperty("content", system);
		msgs.add(sys);
		for (ChatMessage m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}
		body.add("messages", msgs);

		if (jsonMode) {
			JsonObject format = new JsonObject();
			format.addProperty("type", "json_object");
			body.add("response_format", format);
		}

		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(Duration.ofMinutes(15))
				.header("content-type", "application/json")
				.header("authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		HttpResponse<Stream<String>> response = Http.sendStreaming(request, "DeepSeek");
		if (response.statusCode() != 200) {
			String message = Http.errorMessage(Http.drain(response));
			String lower = message.toLowerCase(java.util.Locale.ROOT);
			if (jsonMode && response.statusCode() == 400 && lower.contains("response_format")) {
				throw new JsonModeRejected(message);
			}
			if (response.statusCode() == 400 && lower.contains("max_tokens")) {
				throw new LimitTooHigh(message, largestNumber(message));
			}
			throw new AiException("DeepSeek API error " + response.statusCode() + ": " + message);
		}

		StringBuilder text = new StringBuilder();
		String finish = "";
		boolean thinkingStarted = false;
		boolean writingStarted = false;
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
				String data = line.substring(5).trim();
				if (data.equals("[DONE]")) {
					break;
				}
				JsonObject chunk;
				try {
					chunk = JsonParser.parseString(data).getAsJsonObject();
				} catch (RuntimeException e) {
					continue;
				}
				if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
					JsonObject usage = chunk.getAsJsonObject("usage");
					listener.usage(num(usage, "prompt_tokens"), num(usage, "completion_tokens"));
				}
				JsonArray choices = chunk.has("choices") && chunk.get("choices").isJsonArray() ? chunk.getAsJsonArray("choices") : null;
				if (choices == null || choices.isEmpty()) {
					continue;
				}
				JsonObject choice = choices.get(0).getAsJsonObject();
				JsonObject delta = choice.getAsJsonObject("delta");
				if (delta != null) {
					String reasoning = str(delta, "reasoning_content");
					if (!reasoning.isEmpty()) {
						if (!thinkingStarted) {
							thinkingStarted = true;
							listener.phase(AiListener.THINKING);
						}
						listener.thinking(reasoning);
					}
					String content = str(delta, "content");
					if (!content.isEmpty()) {
						if (!writingStarted) {
							writingStarted = true;
							listener.phase(AiListener.WRITING);
						}
						text.append(content);
						listener.text(content);
					}
				}
				String f = str(choice, "finish_reason");
				if (!f.isEmpty()) {
					finish = f;
				}
			}
		} catch (java.io.UncheckedIOException e) {
			throw new AiException("Connection to DeepSeek dropped: " + e.getMessage(), e);
		}

		if (finish.equals("length")) {
			throw new CutOff();
		}
		if (text.isEmpty()) {
			throw new AiException("DeepSeek returned no text.");
		}
		return text.toString();
	}

	private static long num(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : -1;
	}

	private static String str(JsonObject o, String key) {
		if (o == null || !o.has(key)) {
			return "";
		}
		JsonElement e = o.get(key);
		return e.isJsonPrimitive() ? e.getAsString() : "";
	}
}
