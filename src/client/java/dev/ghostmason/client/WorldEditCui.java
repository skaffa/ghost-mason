package dev.ghostmason.client;

import dev.ghostmason.GhostMason;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.enginehub.worldeditcui.protocol.CUIPacket;
import org.enginehub.worldeditcui.protocol.CUIPacketHandler;

import java.util.List;

/**
 * Talks to WorldEdit through the WorldEditCUI protocol library. Only loaded when that library
 * is installed (see {@link WorldEditSelection}).
 *
 * <p>Events used: "s" (selection shape, e.g. "cuboid") and "p" (point: id, x, y, z, area),
 * where point 0 and 1 are the two cuboid corners.
 */
final class WorldEditCui {
	private static boolean warnedShape;

	private WorldEditCui() {
	}

	static void register() {
		CUIPacketHandler.instance().registerClientboundHandler((packet, context) ->
				Minecraft.getInstance().execute(() -> handle(packet)));
	}

	static void handshake() {
		if (ClientPlayNetworking.canSend(CUIPacket.TYPE)) {
			ClientPlayNetworking.send(new CUIPacket("v", CUIPacket.protocolVersion()));
		}
	}

	private static void handle(CUIPacket packet) {
		if (!Session.config.worldedit) {
			return;
		}
		List<String> args = packet.args();
		try {
			switch (packet.eventType()) {
				case "s" -> {
					boolean cuboid = !args.isEmpty() && args.getFirst().equals("cuboid");
					if (!cuboid && !warnedShape) {
						warnedShape = true;
						Msg.warn("Ghost Mason uses cuboid WorldEdit selections only (//sel cuboid).");
					}
				}
				case "p" -> {
					if (args.size() < 4) {
						return;
					}
					BlockPos pos = new BlockPos(Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)), Integer.parseInt(args.get(3)));
					switch (args.getFirst()) {
						case "0" -> Session.pos1 = pos;
						case "1" -> Session.pos2 = pos;
						default -> {
							return;
						}
					}
					WorldEditSelection.source = "WorldEdit";
				}
				default -> {
					// other shapes, colours, etc. are not needed
				}
			}
		} catch (NumberFormatException e) {
			GhostMason.LOGGER.debug("Ignoring malformed WorldEdit selection event {}", packet);
		}
	}
}
