package dev.ghostmason.core.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Shared HTTP plumbing for the providers. */
final class Http {
	static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.build();

	private Http() {
	}

	static HttpResponse<String> send(HttpRequest request, String providerName) throws AiException {
		try {
			return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AiException("Could not reach " + providerName + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Request to " + providerName + " was interrupted", e);
		}
	}

	/** Sends a request whose response is read line by line as it arrives (server-sent events). */
	static HttpResponse<java.util.stream.Stream<String>> sendStreaming(HttpRequest request, String providerName) throws AiException {
		try {
			return CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
		} catch (IOException e) {
			throw new AiException("Could not reach " + providerName + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiException("Request to " + providerName + " was interrupted", e);
		}
	}

	/** Reads the whole body of a failed streaming response, for the error message. */
	static String drain(HttpResponse<java.util.stream.Stream<String>> response) {
		try (java.util.stream.Stream<String> lines = response.body()) {
			return lines.collect(java.util.stream.Collectors.joining("\n"));
		} catch (RuntimeException e) {
			return "";
		}
	}

	static JsonObject parseObject(String body, String providerName) throws AiException {
		try {
			JsonElement element = JsonParser.parseString(body);
			if (element.isJsonObject()) {
				return element.getAsJsonObject();
			}
		} catch (RuntimeException ignored) {
			// fall through
		}
		throw new AiException(providerName + " returned an unreadable response: " + abbreviate(body));
	}

	/** Pulls error.message out of a JSON error body, or falls back to the raw body. */
	static String errorMessage(String body) {
		try {
			JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
			if (obj.has("error") && obj.get("error").isJsonObject()) {
				JsonObject err = obj.getAsJsonObject("error");
				if (err.has("message")) {
					return err.get("message").getAsString();
				}
			}
		} catch (RuntimeException ignored) {
			// not JSON
		}
		return abbreviate(body);
	}

	static String abbreviate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 300 ? s.substring(0, 300) + "..." : s;
	}
}
