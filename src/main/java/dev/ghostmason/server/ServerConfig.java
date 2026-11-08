package dev.ghostmason.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ghostmason.GhostMason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side limits for /gm apply, in config/ghostmason-server.json. Only matters on the
 * server (or the singleplayer world); the client config is separate.
 */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * Who may place designs: "op" (operators only), "creative" (operators, or anyone in
	 * creative mode), "nobody", or "auto": creative in singleplayer, op on dedicated servers.
	 */
	public String permission = "auto";

	/** Most block changes one /gm apply (or undo/redo) may make. */
	public int maxBlocksPerApply = 20000;

	/** Seconds a player must wait between placements. Operators are exempt. */
	public int cooldownSeconds = 10;

	/** Blocks farther than this from the player are refused. */
	public int maxDistance = 256;

	/** How many placements per player can be undone. */
	public int undoDepth = 10;

	static ServerConfig load(Path file) {
		ServerConfig cfg = null;
		if (Files.exists(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ServerConfig.class);
			} catch (IOException | RuntimeException e) {
				GhostMason.LOGGER.warn("Could not read {}, using defaults: {}", file, e.getMessage());
			}
		}
		if (cfg == null) {
			cfg = new ServerConfig();
		}
		try {
			// Write back so new settings appear in older files.
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
		} catch (IOException e) {
			GhostMason.LOGGER.warn("Could not write {}: {}", file, e.getMessage());
		}
		return cfg;
	}
}
