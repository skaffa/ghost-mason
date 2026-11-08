package dev.ghostmason.client;

import dev.ghostmason.core.ai.ModelList;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab suggestions for /gm config model, taken from the provider's own model list the first
 * time they are needed (in the background), with a small built-in list until then.
 */
final class ModelSuggestions {
	private static final List<String> CLAUDE_CODE = List.of("default", "opus", "sonnet", "haiku");
	private static final Map<String, List<String>> FALLBACK = Map.of(
			"claude", List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
			"deepseek", List.of("deepseek-flash", "deepseek-v4-pro"));

	private static final Map<String, List<String>> FETCHED = new ConcurrentHashMap<>();
	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

	private ModelSuggestions() {
	}

	static List<String> forCurrentProvider() {
		String provider = Session.config.provider.toLowerCase(Locale.ROOT);
		if (provider.equals("claudecode")) {
			return CLAUDE_CODE;
		}
		List<String> fetched = FETCHED.get(provider);
		if (fetched != null && !fetched.isEmpty()) {
			return fetched;
		}
		String key = provider.equals("deepseek") ? Session.config.deepseekApiKey : Session.config.anthropicApiKey;
		if (key != null && !key.isBlank() && REQUESTED.add(provider)) {
			String base = Session.config.deepseekBaseUrl;
			Session.AI_EXECUTOR.execute(() -> FETCHED.put(provider, ModelList.fetch(provider, key.trim(), base)));
		}
		return FALLBACK.getOrDefault(provider, List.of());
	}

	/** Forget fetched lists, e.g. after a new key is linked. */
	static void reset() {
		FETCHED.clear();
		REQUESTED.clear();
	}
}
