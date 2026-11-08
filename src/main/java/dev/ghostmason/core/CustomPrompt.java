package dev.ghostmason.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's own standing instructions for the AI, kept in config/ghostmason-prompt.txt and
 * edited by hand. Read fresh for every request, so changes apply without restarting.
 */
public final class CustomPrompt {
	private static final String TEMPLATE = """
			# Ghost Mason: your own instructions for the AI
			#
			# Everything in this file that is not a comment (lines starting with #) is sent
			# with every design request, as standing instructions from you. Edit it any time;
			# the next request uses the new text. Leave it empty to send nothing.
			#
			# Examples (remove the # to use them):
			# Prefer a medieval style with dark oak, spruce and cobblestone.
			# Always add lanterns so mobs cannot spawn inside.
			# Keep builds symmetrical and leave a 1-block path around them.
			""";

	private CustomPrompt() {
	}

	/** The instructions without comments, or "" when there are none. Creates the file the first time. */
	public static String load(Path file) {
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
				return "";
			}
			StringBuilder sb = new StringBuilder();
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (!line.stripLeading().startsWith("#")) {
					sb.append(line).append('\n');
				}
			}
			return sb.toString().strip();
		} catch (IOException e) {
			return "";
		}
	}
}
