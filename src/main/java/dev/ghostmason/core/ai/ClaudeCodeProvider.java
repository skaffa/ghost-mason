package dev.ghostmason.core.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Claude through the player's own Claude Code install, so requests use whatever account
 * Claude Code is signed in with (for example a Claude Pro or Max subscription).
 *
 * <p>Runs {@code claude -p} with every tool disabled, in an empty temporary folder, with the
 * player's personal settings, hooks, plugins and MCP servers skipped, and the request on stdin.
 */
public final class ClaudeCodeProvider implements AiProvider {
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	private static final long TIMEOUT_MINUTES = 10;

	/**
	 * Claude Code's own system prompt is replaced with this short one. The real instructions go
	 * on stdin: command-line arguments with quotes or angle brackets are unreliable on Windows.
	 */
	private static final String SHORT_SYSTEM_PROMPT =
			"You are Ghost Mason, a Minecraft build designer. Follow the instructions block in the user message exactly and reply with only what it asks for.";

	/** Install and sign-in state, for the link page. */
	public record Status(Path executable, String version, boolean loggedIn, String authMethod) {
		public boolean installed() {
			return executable != null;
		}
	}

	private final Path executable;
	private final String model;
	/** Output token limit passed to Claude Code; 0 leaves its default. */
	private final int maxTokens;

	/** Claude Code's --effort level, or blank for its default. */
	private final String effort;

	public ClaudeCodeProvider(Path executable, String model, int maxTokens, String effort) {
		this.executable = executable;
		this.model = model;
		this.maxTokens = maxTokens;
		this.effort = effort;
	}

	@Override
	public String displayName() {
		return "Claude via Claude Code" + (model.isBlank() ? "" : " (" + model + ")");
	}

	// --- Finding Claude Code -----------------------------------------------------------------

	/** Finds the claude executable: the configured path, then PATH, then the native installer's folder. */
	public static Optional<Path> locate(String configuredPath) {
		if (configuredPath != null && !configuredPath.isBlank()) {
			Path p = Path.of(configuredPath.trim());
			return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
		}
		List<Path> dirs = new ArrayList<>();
		String path = System.getenv("PATH");
		if (path != null) {
			for (String d : path.split(java.io.File.pathSeparator)) {
				if (!d.isBlank()) {
					try {
						dirs.add(Path.of(d.trim()));
					} catch (RuntimeException ignored) {
						// malformed PATH entry
					}
				}
			}
		}
		dirs.add(Path.of(System.getProperty("user.home"), ".local", "bin"));

		for (Path dir : dirs) {
			if (WINDOWS) {
				Path exe = dir.resolve("claude.exe");
				if (Files.isRegularFile(exe)) {
					return Optional.of(exe);
				}
				// npm install: claude.cmd is a wrapper around a native exe in node_modules.
				if (Files.isRegularFile(dir.resolve("claude.cmd"))) {
					Path nativeExe = dir.resolve("node_modules/@anthropic-ai/claude-code/bin/claude.exe");
					if (Files.isRegularFile(nativeExe)) {
						return Optional.of(nativeExe);
					}
				}
			} else {
				Path bin = dir.resolve("claude");
				if (Files.isRegularFile(bin) && Files.isExecutable(bin)) {
					return Optional.of(bin);
				}
			}
		}
		return Optional.empty();
	}

	/** Checks whether Claude Code is installed and signed in. Takes a second or two. */
	public static Status status(String configuredPath) {
		Optional<Path> exe = locate(configuredPath);
		if (exe.isEmpty()) {
			return new Status(null, "", false, "");
		}
		String version = "";
		boolean loggedIn = false;
		String authMethod = "";
		try {
			Result v = run(exe.get(), List.of("--version"), "", 20, TimeUnit.SECONDS);
			version = v.stdout().trim().split("\\s+")[0];
			Result s = run(exe.get(), List.of("auth", "status", "--json"), "", 20, TimeUnit.SECONDS);
			JsonObject json = JsonParser.parseString(s.stdout().trim()).getAsJsonObject();
			loggedIn = json.has("loggedIn") && json.get("loggedIn").getAsBoolean();
			authMethod = json.has("authMethod") && !json.get("authMethod").isJsonNull() ? json.get("authMethod").getAsString() : "";
		} catch (IOException | RuntimeException e) {
			// treat as not signed in
		}
		return new Status(exe.get(), version, loggedIn, authMethod);
	}

	// --- Requests ------------------------------------------------------------------------------

	@Override
	public String complete(String system, List<ChatMessage> messages, boolean wantThinking, AiListener listener) throws AiException {
		List<String> args = new ArrayList<>(List.of(
				"-p",
				"--output-format", "stream-json",
				"--verbose",
				"--include-partial-messages",
				"--tools", "",
				"--setting-sources", "project",
				"--strict-mcp-config",
				"--disable-slash-commands",
				"--no-session-persistence",
				"--system-prompt", SHORT_SYSTEM_PROMPT));
		if (!model.isBlank()) {
			args.add("--model");
			args.add(model);
		}
		if (!effort.isBlank()) {
			args.add("--effort");
			args.add(effort);
		}

		Result result;
		JsonObject[] finalEvent = new JsonObject[1];
		try {
			result = runStreaming(executable, args, flatten(system, messages), line -> {
				JsonObject event;
				try {
					event = JsonParser.parseString(line).getAsJsonObject();
				} catch (RuntimeException e) {
					return;
				}
				String type = str(event, "type");
				if (type.equals("result")) {
					finalEvent[0] = event;
				} else if (type.equals("stream_event") && event.has("event")) {
					JsonObject e = event.getAsJsonObject("event");
					Usage.report(e, listener);
					switch (str(e, "type")) {
						case "content_block_start" -> {
							String blockType = str(e.getAsJsonObject("content_block"), "type");
							if (blockType.equals("thinking")) {
								listener.phase(AiListener.THINKING);
							} else if (blockType.equals("text")) {
								listener.phase(AiListener.WRITING);
							}
						}
						case "content_block_delta" -> {
							JsonObject delta = e.getAsJsonObject("delta");
							switch (str(delta, "type")) {
								case "text_delta" -> listener.text(str(delta, "text"));
								case "thinking_delta" -> {
									String t = str(delta, "thinking");
									if (!t.isEmpty()) {
										listener.thinking(t);
									}
								}
								default -> {
									// signatures etc.
								}
							}
						}
						default -> {
							// other stream events are not needed
						}
					}
				}
			}, listener::cancelled, TIMEOUT_MINUTES, TimeUnit.MINUTES);
		} catch (IOException e) {
			throw new AiException("Could not run Claude Code: " + e.getMessage(), e);
		}
		if (listener.cancelled()) {
			throw new CancelledException();
		}

		JsonObject json = finalEvent[0];
		if (json == null) {
			String detail = (result.stderr() + " " + result.stdout()).trim();
			if (looksLikeAuthProblem(detail)) {
				throw new MissingKeyException("claudecode", "Claude Code is not signed in.");
			}
			throw new AiException("Claude Code failed (exit " + result.exitCode() + "): " + Http.abbreviate(detail));
		}

		String text = json.has("result") && !json.get("result").isJsonNull() ? json.get("result").getAsString() : "";
		boolean isError = json.has("is_error") && json.get("is_error").getAsBoolean();
		String stopReason = json.has("stop_reason") && !json.get("stop_reason").isJsonNull() ? json.get("stop_reason").getAsString() : "";

		if (isError) {
			if (looksLikeAuthProblem(text)) {
				throw new MissingKeyException("claudecode", "Claude Code is not signed in.");
			}
			throw new AiException("Claude Code reported an error: " + Http.abbreviate(text));
		}
		if (stopReason.equals("refusal")) {
			throw new AiException("Claude declined this request. Try rephrasing it.");
		}
		if (stopReason.equals("max_tokens")) {
			throw new AiException("Claude's answer was cut off. Raise the limit with /gm config maxtokens <n>, or use a smaller selection.");
		}
		if (text.isBlank()) {
			throw new AiException("Claude Code returned no text.");
		}
		return text;
	}

	/** Claude Code takes one prompt, so earlier turns are written out as a transcript. */
	static String flatten(String system, List<ChatMessage> messages) {
		StringBuilder sb = new StringBuilder();
		sb.append("<instructions>\n").append(system).append("\n</instructions>\n\n");
		if (messages.size() == 1) {
			sb.append(messages.getFirst().content());
			return sb.toString();
		}
		sb.append("<conversation>\n");
		for (ChatMessage m : messages) {
			sb.append("<turn role=\"").append(m.role()).append("\">\n").append(m.content()).append("\n</turn>\n");
		}
		sb.append("</conversation>\n\nAnswer the last user turn, following the instructions.");
		return sb.toString();
	}

	private static boolean looksLikeAuthProblem(String text) {
		String t = text.toLowerCase(Locale.ROOT);
		return t.contains("not logged in") || t.contains("/login") || t.contains("please log in")
				|| t.contains("invalid api key") || t.contains("authentication");
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	/**
	 * Runs Claude Code and hands each stdout line to {@code onLine} as it arrives.
	 * The returned result's stdout is empty; stderr is collected for error messages.
	 */
	private Result runStreaming(Path exe, List<String> args, String stdin, java.util.function.Consumer<String> onLine,
			java.util.function.BooleanSupplier cancelled, long timeout, TimeUnit unit) throws IOException {
		Path dir = Files.createTempDirectory("ghostmason-claude");
		Path err = dir.resolve("err.txt");
		Path work = Files.createDirectory(dir.resolve("work"));
		try {
			List<String> command = new ArrayList<>();
			command.add(exe.toString());
			command.addAll(args);
			ProcessBuilder pb = new ProcessBuilder(command)
					.directory(work.toFile())
					.redirectError(err.toFile());
			pb.environment().remove("ANTHROPIC_API_KEY");
			if (maxTokens > 0) {
				pb.environment().put("CLAUDE_CODE_MAX_OUTPUT_TOKENS", String.valueOf(maxTokens));
			}
			Process process = pb.start();

			// Kill the process if it runs too long or the player cancels, so the reader below stops.
			long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
			Thread watchdog = new Thread(() -> {
				try {
					while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
						if (cancelled.getAsBoolean() || System.currentTimeMillis() > deadline) {
							process.destroyForcibly();
							return;
						}
					}
				} catch (InterruptedException ignored) {
					process.destroyForcibly();
				}
			}, "Ghost Mason Claude Code watchdog");
			watchdog.setDaemon(true);
			watchdog.start();

			try (var stdinStream = process.getOutputStream()) {
				stdinStream.write(stdin.getBytes(StandardCharsets.UTF_8));
			}
			try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.isBlank()) {
						onLine.accept(line);
					}
				}
			}
			int exit;
			try {
				exit = process.waitFor();
			} catch (InterruptedException e) {
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", e);
			}
			watchdog.interrupt();
			return new Result(exit, "", Files.readString(err, StandardCharsets.UTF_8));
		} finally {
			deleteTree(dir);
		}
	}

	// --- Process plumbing ------------------------------------------------------------------------

	private record Result(int exitCode, String stdout, String stderr) {
	}

	private static Result run(Path exe, List<String> args, String stdin, long timeout, TimeUnit unit) throws IOException {
		Path dir = Files.createTempDirectory("ghostmason-claude");
		Path out = dir.resolve("out.txt");
		Path err = dir.resolve("err.txt");
		// Work inside an empty folder so Claude Code finds no project files or CLAUDE.md.
		Path work = Files.createDirectory(dir.resolve("work"));
		try {
			List<String> command = new ArrayList<>();
			command.add(exe.toString());
			command.addAll(args);
			ProcessBuilder pb = new ProcessBuilder(command)
					.directory(work.toFile())
					.redirectOutput(out.toFile())
					.redirectError(err.toFile());
			// Use Claude Code's signed-in account, not an API key that happens to be in the environment.
			pb.environment().remove("ANTHROPIC_API_KEY");
			Process process = pb.start();
			try (var stdinStream = process.getOutputStream()) {
				stdinStream.write(stdin.getBytes(StandardCharsets.UTF_8));
			}
			boolean finished;
			try {
				finished = process.waitFor(timeout, unit);
			} catch (InterruptedException e) {
				process.destroyForcibly();
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", e);
			}
			if (!finished) {
				process.destroyForcibly();
				throw new IOException("Claude Code did not answer within " + timeout + " " + unit.name().toLowerCase(Locale.ROOT));
			}
			return new Result(process.exitValue(),
					Files.readString(out, StandardCharsets.UTF_8),
					Files.readString(err, StandardCharsets.UTF_8));
		} finally {
			deleteTree(dir);
		}
	}

	private static void deleteTree(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best effort
				}
			});
		} catch (IOException ignored) {
			// best effort
		}
	}
}
