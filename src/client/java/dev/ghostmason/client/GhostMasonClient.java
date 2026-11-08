package dev.ghostmason.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Client entrypoint: commands, AI requests and ghost rendering. */
public class GhostMasonClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register(GmCommands::register);
		ClientTickEvents.END_CLIENT_TICK.register(GhostRenderer::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(GhostBlockRenderer::render);

		WorldEditSelection.init();
		// Create the instructions file with its explanation, so players can find it.
		dev.ghostmason.core.CustomPrompt.load(Session.CONFIG_PATH.resolveSibling("ghostmason-prompt.txt"));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				client.execute(WorldEditSelection::requestUpdates));
	}
}
