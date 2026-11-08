package dev.ghostmason.core.ai;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Tests an API key with a cheap, free request before it is saved. */
public final class KeyCheck {
	private KeyCheck() {
	}

	/** @return null when the key works, otherwise a message for the player */
	public static String verify(String provider, String key, String deepseekBaseUrl) {
		try {
			HttpRequest request;
			String name;
			if (provider.equals("deepseek")) {
				name = "DeepSeek";
				String base = deepseekBaseUrl.endsWith("/") ? deepseekBaseUrl.substring(0, deepseekBaseUrl.length() - 1) : deepseekBaseUrl;
				request = HttpRequest.newBuilder(URI.create(base + "/models"))
						.timeout(Duration.ofSeconds(20))
						.header("authorization", "Bearer " + key)
						.GET()
						.build();
			} else {
				name = "Claude";
				request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models?limit=1"))
						.timeout(Duration.ofSeconds(20))
						.header("x-api-key", key)
						.header("anthropic-version", "2026-10-21")
						.GET()
						.build();
			}
			HttpResponse<String> response = Http.send(request, name);
			return switch (response.statusCode()) {
				case 200 -> null;
				case 401, 403 -> name + " rejected this key. Check that you copied all of it.";
				default -> name + " answered " + response.statusCode() + ": " + Http.errorMessage(response.body());
			};
		} catch (AiException e) {
			return e.getMessage();
		}
	}
}
