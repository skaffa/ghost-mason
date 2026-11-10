package dev.ghostmason.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ghostmason.core.ai.AiException;
import dev.ghostmason.core.ai.AiProvider;
import dev.ghostmason.core.ai.AnthropicProvider;
import dev.ghostmason.core.ai.ClaudeCodeProvider;
import dev.ghostmason.core.ai.DeepSeekProvider;
import dev.ghostmason.core.ai.MissingKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** User settings, stored as JSON in config/ghostmason/client.json. Public fields map 1:1 to JSON keys. */
public final class GhostMasonConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** "claude" (Anthropic API key), "claudecode" (your local Claude Code install) or "deepseek". */
	public String provider = "claude";

	/**
	 * How much of the design process is shown in chat: "off" (result only), "brief" (progress
	 * and the AI's design notes) or "full" (also its reasoning, where the provider exposes it).
	 */
	public String verbosity = "brief";

	/** Show a live token count in the action bar while designing, and the final count in chat. */
	public boolean showTokens = false;

	/**
	 * "checked" (default): blocks that could not stay where planned (a torch on a glass pane)
	 * are re-aimed or left out. "free": place every block exactly as designed, supported or not.
	 */
	public String placement = "checked";

	/** Use the WorldEdit selection (needs WorldEdit or WorldEditCUI installed on the client). */
	public boolean worldedit = false;

	/**
	 * After each design, let the AI look over its own result once and fix what it finds.
	 * Roughly doubles the time per design.
	 */
	public boolean review = false;

	/** Send the game's own list of blocks and properties with each request (more accurate, ~6k tokens). */
	public boolean blockReference = true;

	/** Path to the claude executable. Leave blank to find it automatically. */
	public String claudeCodePath = "";
	/** Model alias or id for Claude Code (e.g. opus, sonnet). Leave blank for Claude Code's default. */
	public String claudeCodeModel = "";
	/** Output token limit per answer for Claude Code; 0 uses Claude Code's own default. */
	public int claudeCodeMaxTokens = 0;
	/** Claude Code's --effort level (low, medium, high, xhigh, max), or blank for its default. */
	public String claudeCodeEffort = "";

	/** Leave blank to use the ANTHROPIC_API_KEY environment variable. */
	public String anthropicApiKey = "";
	public String anthropicModel = "claude-opus-5-5";
	/** low / medium / high / xhigh / max, or blank to omit. */
	public String anthropicEffort = "medium";
	public int anthropicMaxTokens = 64000;

	/** Leave blank to use the DEEPSEEK_API_KEY environment variable. */
	public String deepseekApiKey = "";
	public String deepseekModel = "deepseek-flash";
	public String deepseekBaseUrl = "https://api.deepseek.com";
	/**
	 * How long DeepSeek thinks: "off" (no reasoning), "low", "high" (DeepSeek's own default) or "max".
	 * Low keeps designs quick; raise it for complex builds.
	 */
	public String deepseekEffort = "low";
	/**
	 * Output limit per answer, reasoning included. DeepSeek reasons at length and bills only
	 * what is used, so this starts high. A limit the model rejects is lowered to the maximum it reports.
	 */
	public int deepseekMaxTokens = 524288;

	/** Largest selection (in blocks) that may be sent to the AI. */
	public int maxRegionVolume = 32768;
	/** Largest number of block changes a single plan may contain. */
	public int maxChanges = 100000;
	/** Ghosts farther than this from the player are not drawn. */
	public int ghostRenderDistance = 48;
	/** Upper bound on ghosts drawn per tick. */
	public int maxGhostsDrawn = 4000;

	public static GhostMasonConfig load(Path file) {
		if (Files.exists(file)) {
			try {
				GhostMasonConfig cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), GhostMasonConfig.class);
				if (cfg != null) {
					return cfg;
				}
			} catch (IOException | RuntimeException e) {
				System.err.println("[Ghost Mason] Could not read " + file + ", using defaults: " + e.getMessage());
			}
		}
		GhostMasonConfig cfg = new GhostMasonConfig();
		cfg.save(file);
		return cfg;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.err.println("[Ghost Mason] Could not write " + file + ": " + e.getMessage());
		}
	}

	public AiProvider createProvider() throws AiException {
		return createProvider(false);
	}

	/**
	 * @param quick use the provider's least thinking (DeepSeek: off, Claude: low), for when the
	 *              player cut the thinking short and wants the build right away
	 */
	public AiProvider createProvider(boolean quick) throws AiException {
		switch (provider.toLowerCase(Locale.ROOT)) {
			case "claudecode", "claude-code" -> {
				Path exe = ClaudeCodeProvider.locate(claudeCodePath).orElseThrow(() ->
						new MissingKeyException("claudecode", "Claude Code is not installed."));
				return new ClaudeCodeProvider(exe, claudeCodeModel == null ? "" : claudeCodeModel.trim(), claudeCodeMaxTokens,
						quick ? "low" : claudeCodeEffort == null ? "" : claudeCodeEffort.trim());
			}
			case "claude", "anthropic" -> {
				String key = keyOrEnv(anthropicApiKey, "ANTHROPIC_API_KEY");
				if (key.isEmpty()) {
					throw new MissingKeyException("claude", "No Claude API key linked yet.");
				}
				return new AnthropicProvider(key, anthropicModel, anthropicMaxTokens, quick ? "low" : anthropicEffort);
			}
			case "deepseek" -> {
				String key = keyOrEnv(deepseekApiKey, "DEEPSEEK_API_KEY");
				if (key.isEmpty()) {
					throw new MissingKeyException("deepseek", "No DeepSeek API key linked yet.");
				}
				return new DeepSeekProvider(key, deepseekModel, deepseekBaseUrl, deepseekMaxTokens,
						quick ? "off" : deepseekEffort == null ? "" : deepseekEffort.trim());
			}
			default -> throw new AiException("Unknown provider '" + provider + "'. Use claudecode, claude or deepseek.");
		}
	}

	private static String keyOrEnv(String key, String envVar) {
		if (key != null && !key.isBlank()) {
			return key.trim();
		}
		String env = System.getenv(envVar);
		return env == null ? "" : env.trim();
	}
}
