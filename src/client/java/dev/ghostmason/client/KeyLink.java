package dev.ghostmason.client;

import dev.ghostmason.GhostMason;
import dev.ghostmason.core.ai.ClaudeCodeProvider;
import dev.ghostmason.core.ai.KeyCheck;
import dev.ghostmason.core.link.KeyLinkServer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.io.IOException;
import java.time.Duration;

/** The "connect an AI" flow: serves a local page, then resumes whatever was waiting for it. */
final class KeyLink {
	private static final Duration LIFETIME = Duration.ofMinutes(15);

	private static KeyLinkServer server;
	/** Runs once an AI is connected, e.g. the /gm build that needed it. */
	private static Runnable pending;

	private KeyLink() {
	}

	/**
	 * Shows a clickable link to the connect page.
	 *
	 * @param provider which provider to preselect
	 * @param resume   what to run after connecting, or null
	 */
	static void begin(String provider, Runnable resume) {
		pending = resume;
		if (server == null || !server.isOpen()) {
			try {
				server = KeyLinkServer.start(provider,
						() -> ClaudeCodeProvider.status(Session.config.claudeCodePath),
						KeyLink::onLink, LIFETIME);
			} catch (IOException e) {
				GhostMason.LOGGER.error("Could not start the connect page", e);
				Msg.error("Could not open the connect page (" + e.getMessage() + ").");
				return;
			}
		}

		Component link = Component.literal("[Connect Claude or DeepSeek]")
				.withStyle(style -> style
						.withColor(ChatFormatting.AQUA)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.OpenUrl(server.uri()))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(
								"Opens a page served by Minecraft on this computer"))));
		Msg.send(Component.literal("No AI connected yet. ").withStyle(ChatFormatting.YELLOW)
				.append(link)
				.append(Component.literal(" (link works for 15 minutes)").withStyle(ChatFormatting.GRAY)));
	}

	/** Called on the page's thread. Checks the choice, then hands it to the client thread. */
	private static String onLink(String provider, String key) {
		if (provider.equals("claudecode")) {
			ClaudeCodeProvider.Status status = ClaudeCodeProvider.status(Session.config.claudeCodePath);
			if (!status.installed()) {
				return "Claude Code was not found. Install it, then try again.";
			}
			if (!status.loggedIn()) {
				return "Claude Code is installed but not signed in. Run claude, type /login, then try again.";
			}
		} else {
			String error = KeyCheck.verify(provider, key, Session.config.deepseekBaseUrl);
			if (error != null) {
				return error;
			}
		}
		Minecraft.getInstance().execute(() -> connected(provider, key));
		return null;
	}

	private static void connected(String provider, String key) {
		switch (provider) {
			case "deepseek" -> Session.config.deepseekApiKey = key;
			case "claude" -> Session.config.anthropicApiKey = key;
			default -> {
				// Claude Code keeps its own sign-in; nothing to store.
			}
		}
		Session.config.provider = provider;
		Session.saveConfig();
		ModelSuggestions.reset();
		server = null;
		Msg.good(switch (provider) {
			case "deepseek" -> "DeepSeek is connected.";
			case "claude" -> "Anthropic API key is connected.";
			default -> "Claude Code is connected. Requests use your Claude plan.";
		});

		Runnable resume = pending;
		pending = null;
		if (resume != null) {
			resume.run();
		}
	}
}
