package dev.ghostmason.core;

import dev.ghostmason.GhostMason;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Every file Ghost Mason keeps lives in its own folder, {@code config/ghostmason/}. Version 0.1.0
 * kept them loose in {@code config/}; {@link #file} moves such a file into the folder the first
 * time it is asked for.
 */
public final class ConfigFiles {
	/** Client settings, including API keys. Was {@code config/ghostmason.json}. */
	public static final String CLIENT = "client.json";
	/** The player's standing instructions for the AI. Was {@code config/ghostmason-prompt.txt}. */
	public static final String PROMPT = "prompt.txt";
	/** Server-side placement limits. Was {@code config/ghostmason-server.json}. */
	public static final String SERVER = "server.json";

	private ConfigFiles() {
	}

	/** {@code config/ghostmason/}. */
	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(GhostMason.MOD_ID);
	}

	/**
	 * The path of {@code name} inside {@link #dir()}. If that file does not exist yet but the old
	 * loose file {@code config/<oldName>} does, the old file is moved there first.
	 */
	public static Path file(String name, String oldName) {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path file = dir().resolve(name);
		migrate(configDir.resolve(oldName), file);
		return file;
	}

	/**
	 * Moves {@code oldFile} to {@code newFile} when only the old one exists. If the move fails, the
	 * old file is copied instead and renamed to {@code <name>.migrated} so it is not read again.
	 */
	static void migrate(Path oldFile, Path newFile) {
		if (Files.exists(newFile) || !Files.isRegularFile(oldFile)) {
			return;
		}
		try {
			Files.createDirectories(newFile.getParent());
			Files.move(oldFile, newFile);
			GhostMason.LOGGER.info("Moved {} to {}", oldFile, newFile);
			return;
		} catch (IOException e) {
			GhostMason.LOGGER.warn("Could not move {} to {}, copying it instead", oldFile, newFile, e);
		}
		try {
			Files.copy(oldFile, newFile);
			Files.move(oldFile, oldFile.resolveSibling(oldFile.getFileName() + ".migrated"),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			GhostMason.LOGGER.warn("Could not copy {} to {}", oldFile, newFile, e);
		}
	}
}
