package dev.ghostmason.client;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional WorldEdit selection sync. WorldEdit tells clients about selections through the
 * WorldEditCUI protocol; that library ships with both WorldEdit and WorldEditCUI. When it is
 * not installed, or the option is off, /gm pos1 and /gm pos2 are used as usual.
 *
 * <p>This class never touches the protocol library itself, so it is safe to load without it;
 * {@link WorldEditCui} does, and is only called after the check below.
 */
final class WorldEditSelection {
	private static final boolean AVAILABLE = FabricLoader.getInstance().isModLoaded("worldeditcui_protocol");

	/** Where the current selection came from, for status messages. */
	static String source = "Ghost Mason";

	private WorldEditSelection() {
	}

	static boolean available() {
		return AVAILABLE;
	}

	static void init() {
		if (AVAILABLE) {
			WorldEditCui.register();
		}
	}

	/** Asks the server's WorldEdit to start sending selection updates. */
	static void requestUpdates() {
		if (AVAILABLE && Session.config.worldedit) {
			WorldEditCui.handshake();
		}
	}
}
