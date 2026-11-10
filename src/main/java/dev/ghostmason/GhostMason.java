package dev.ghostmason;

import dev.ghostmason.net.ApplyChunkPayload;
import dev.ghostmason.net.HistoryPayload;
import dev.ghostmason.server.ServerPlacer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint: runs on both the client and dedicated servers. */
public class GhostMason implements ModInitializer {
	public static final String MOD_ID = "ghostmason";
	public static final Logger LOGGER = LoggerFactory.getLogger("Ghost Mason");

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(ApplyChunkPayload.TYPE, ApplyChunkPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HistoryPayload.TYPE, HistoryPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ApplyChunkPayload.TYPE, ServerPlacer::onApply);
		ServerPlayNetworking.registerGlobalReceiver(HistoryPayload.TYPE, ServerPlacer::onHistory);

		ServerLifecycleEvents.SERVER_STARTING.register(server ->
				ServerPlacer.onServerStarting(server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerPlacer.forget(handler.getPlayer().getUUID()));
	}
}
