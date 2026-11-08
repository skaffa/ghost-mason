package dev.ghostmason.client;

import dev.ghostmason.core.NotesExtractor;
import dev.ghostmason.core.RegionSnapshot;
import dev.ghostmason.core.ai.AiListener;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shows the design process while the AI works, as much as the settings allow.
 * <ul>
 *   <li>verbosity off: nothing until the result</li>
 *   <li>verbosity brief: stage changes, the AI's design notes as they are written, a heartbeat every 20 s</li>
 *   <li>verbosity full: brief, plus all of the AI's reasoning where the provider exposes it</li>
 *   <li>tokens on: a live token count in the action bar, and the final count in chat</li>
 * </ul>
 * Callbacks arrive on the AI thread; everything is posted to the client thread.
 */
final class DesignProgress implements AiListener {
	private static final int THINKING_LINE_CHARS = 110;
	/** Rough characters per token, for the live estimate before the provider reports real numbers. */
	private static final double CHARS_PER_TOKEN = 3.6;

	private final boolean brief;
	private final boolean full;
	private final boolean showTokens;
	private final RegionSnapshot request;
	private final long started = System.currentTimeMillis();
	private final NotesExtractor notes;
	private final StringBuilder thinking = new StringBuilder();
	private boolean saidThinking;
	private boolean saidWriting;
	private ScheduledFuture<?> heartbeat;
	private ScheduledFuture<?> ticker;

	private volatile boolean cancelled;
	private volatile boolean keepOnCancel;
	/** The answer text so far, for keeping a partial design when cancelled. */
	private final StringBuilder answer = new StringBuilder();
	/** All visible reasoning so far, handed back to the AI when the player cuts the thinking short. */
	private final StringBuilder reasoning = new StringBuilder();

	private volatile long streamedChars;
	private volatile long inputTokens = -1;
	private volatile long outputTokens = -1;

	DesignProgress(String verbosity, boolean showTokens, RegionSnapshot request) {
		String v = verbosity == null ? "brief" : verbosity.toLowerCase(Locale.ROOT);
		this.full = v.equals("full");
		this.brief = full || v.equals("brief");
		this.showTokens = showTokens;
		this.request = request;
		this.notes = new NotesExtractor(note -> {
			if (brief) {
				post(Component.literal("  • " + note).withStyle(ChatFormatting.WHITE));
			}
		});
	}

	boolean wantsThinking() {
		return full;
	}

	void start() {
		if (brief) {
			heartbeat = Session.TIMER.scheduleAtFixedRate(() -> {
				long secs = (System.currentTimeMillis() - started) / 1000;
				int ops = notes.opsSoFar();
				post(Component.literal("Still designing... " + secs + " s" + (ops > 0 ? ", " + ops + " build steps written" : ""))
						.withStyle(ChatFormatting.DARK_GRAY));
			}, 20, 20, TimeUnit.SECONDS);
		}
		if (showTokens) {
			ticker = Session.TIMER.scheduleAtFixedRate(() -> overlay(false), 0, 1, TimeUnit.SECONDS);
		}
	}

	void stop() {
		if (heartbeat != null) {
			heartbeat.cancel(false);
		}
		if (ticker != null) {
			ticker.cancel(false);
		}
		flushThinking(true);
		if (showTokens) {
			overlay(true);
			if (outputTokens >= 0) {
				post(Component.literal("Used " + fmt(Math.max(0, inputTokens)) + " input + " + fmt(outputTokens) + " output tokens.")
						.withStyle(ChatFormatting.DARK_GRAY));
			}
		}
	}

	@Override
	public void phase(String phase) {
		if (!brief) {
			return;
		}
		if (phase.equals(THINKING) && !saidThinking) {
			saidThinking = true;
			post(Component.literal("Thinking...").withStyle(ChatFormatting.GRAY));
		} else if (phase.equals(WRITING) && !saidWriting) {
			saidWriting = true;
			flushThinking(true);
			post(Component.literal("Drafting the build:").withStyle(ChatFormatting.GRAY));
		}
	}

	@Override
	public void text(String delta) {
		streamedChars += delta.length();
		synchronized (answer) {
			answer.append(delta);
		}
		notes.accept(delta);
	}

	/** Asks the provider to stop; with keep, what was designed so far is used. */
	void cancel(boolean keep) {
		keepOnCancel = keep;
		cancelled = true;
	}

	@Override
	public boolean cancelled() {
		return cancelled;
	}

	boolean keepOnCancel() {
		return keepOnCancel;
	}

	String answerSoFar() {
		synchronized (answer) {
			return answer.toString();
		}
	}

	String reasoningSoFar() {
		synchronized (reasoning) {
			return reasoning.toString();
		}
	}

	@Override
	public void thinking(String delta) {
		streamedChars += delta.length();
		synchronized (reasoning) {
			reasoning.append(delta);
		}
		if (!full) {
			return;
		}
		thinking.append(delta);
		flushThinking(false);
	}

	@Override
	public void usage(long input, long output) {
		if (input >= 0) {
			inputTokens = input;
		}
		if (output >= 0) {
			outputTokens = output;
		}
	}

	/** Sends finished lines of reasoning, wrapped to a readable width. */
	private void flushThinking(boolean all) {
		while (!thinking.isEmpty()) {
			int nl = thinking.indexOf("\n");
			int cut;
			if (nl >= 0 && nl <= THINKING_LINE_CHARS) {
				cut = nl;
			} else if (thinking.length() > THINKING_LINE_CHARS) {
				int space = thinking.lastIndexOf(" ", THINKING_LINE_CHARS);
				cut = space > 40 ? space : THINKING_LINE_CHARS;
			} else if (all) {
				cut = thinking.length();
			} else {
				return;
			}
			String line = thinking.substring(0, cut).trim();
			thinking.delete(0, Math.min(thinking.length(), cut + 1));
			if (!line.isEmpty()) {
				post(Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
			}
		}
	}

	/** Token count in the action bar: exact once the provider reports it, estimated before. */
	private void overlay(boolean done) {
		long secs = (System.currentTimeMillis() - started) / 1000;
		String text;
		if (outputTokens >= 0) {
			text = (done ? "Done: " : "") + fmt(Math.max(0, inputTokens)) + " in · " + fmt(outputTokens) + " out tokens";
		} else {
			long estimate = Math.round(streamedChars / CHARS_PER_TOKEN);
			text = (done ? "Done: ~" : "~") + fmt(estimate) + " tokens"
					+ (streamedChars == 0 && !done ? " (hidden reasoning is not counted until the end)" : "");
		}
		Component line = Component.literal("Ghost Mason: " + text + (done ? "" : " · " + secs + " s")).withStyle(ChatFormatting.AQUA);
		Minecraft.getInstance().execute(() -> {
			if (Session.snapshot == request && Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.sendOverlayMessage(line);
			}
		});
	}

	private static String fmt(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	private void post(Component line) {
		Minecraft.getInstance().execute(() -> {
			if (Session.snapshot == request) {
				Msg.send(line);
			}
		});
	}
}
