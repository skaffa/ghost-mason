package dev.ghostmason.core.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Asks a provider which models it offers, so suggestions never go out of date. */
public final class ModelList {
	private ModelList() {
	}

	/** @return model ids, or an empty list when the provider could not be asked */
	public static List<String> fetch(String provider, String key, String deepseekBaseUrl) {
		try {
			HttpRequest request;
			if (provider.equals("deepseek")) {
				String base = deepseekBaseUrl.endsWith("/") ? deepseekBaseUrl.substring(0, deepseekBaseUrl.length() - 1) : deepseekBaseUrl;
				request = HttpRequest.newBuilder(URI.create(base + "/models"))
						.timeout(Duration.ofSeconds(15))
						.header("authorization", "Bearer " + key)
						.GET().build();
			} else {
				request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models?limit=100"))
						.timeout(Duration.ofSeconds(15))
						.header("x-api-key", key)
						.header("anthropic-version", "2026-10-21")
						.GET().build();
			}
			HttpResponse<String> response = Http.send(request, provider);
			if (response.statusCode() != 200) {
				return List.of();
			}
			JsonObject json = Http.parseObject(response.body(), provider);
			List<String> ids = new ArrayList<>();
			if (json.has("data") && json.get("data").isJsonArray()) {
				for (JsonElement e : json.getAsJsonArray("data")) {
					if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
						ids.add(e.getAsJsonObject().get("id").getAsString());
					}
				}
			}
			return ids;
		} catch (AiException | RuntimeException e) {
			return List.of();
		}
	}
}
