package dev.ghostmason.server;

import dev.ghostmason.GhostMason;
import dev.ghostmason.core.ConfigFiles;
import dev.ghostmason.net.ApplyChunkPayload;
import dev.ghostmason.net.HistoryPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Places designs sent by clients, within the limits of {@link ServerConfig}, and keeps a short
 * undo/redo history per player. Clients without the server side simply cannot place; they can
 * still build along the ghosts by hand.
 */
public final class ServerPlacer {
	private record Batch(int id, ServerLevel level, List<ApplyChunkPayload.Entry> previous, int[] counts, boolean rejected) {
		// counts: [0] placed, [1] skipped containers, [2] skipped out of range/unloaded, [3] over the cap
	}

	private static ServerConfig config = new ServerConfig();
	private static boolean dedicated;

	private static final Map<UUID, Batch> PENDING = new HashMap<>();
	private static final Map<UUID, Deque<Batch>> HISTORY = new HashMap<>();
	private static final Map<UUID, Deque<Batch>> REDO = new HashMap<>();
	private static final Map<UUID, Long> LAST_PLACED = new HashMap<>();

	private ServerPlacer() {
	}

	public static void onServerStarting(MinecraftServer server) {
		config = ServerConfig.load(ConfigFiles.file(ConfigFiles.SERVER, "ghostmason-server.json"));
		dedicated = server.isDedicatedServer();
		PENDING.clear();
		HISTORY.clear();
		REDO.clear();
		LAST_PLACED.clear();
	}

	public static void forget(UUID player) {
		PENDING.remove(player);
	}

	private static boolean isOp(ServerPlayer player) {
		return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static boolean allowed(ServerPlayer player) {
		String mode = config.permission.toLowerCase(Locale.ROOT);
		if (mode.equals("auto")) {
			mode = dedicated ? "op" : "creative";
		}
		return switch (mode) {
			case "nobody" -> false;
			case "creative" -> isOp(player) || player.isCreative();
			default -> isOp(player);
		};
	}

	private static String permissionHint() {
		String mode = config.permission.toLowerCase(Locale.ROOT);
		if (mode.equals("auto")) {
			mode = dedicated ? "op" : "creative";
		}
		return switch (mode) {
			case "nobody" -> "Placing designs is turned off on this server.";
			case "creative" -> "Placing designs needs creative mode or operator rights.";
			default -> "Placing designs needs operator rights on this server.";
		};
	}

	public static void onApply(ApplyChunkPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		UUID id = player.getUUID();
		ServerLevel level = player.level();

		Batch batch = PENDING.get(id);
		if (batch == null || batch.id() != payload.batchId() || batch.level() != level) {
			// First part of a new placement: check permission and cooldown once.
			String refusal = null;
			if (!allowed(player)) {
				refusal = permissionHint() + " Build along the ghosts by hand instead.";
			} else if (!isOp(player)) {
				long wait = LAST_PLACED.getOrDefault(id, 0L) + config.cooldownSeconds * 1000L - System.currentTimeMillis();
				if (wait > 0) {
					refusal = "Wait " + (wait / 1000 + 1) + " s before placing again.";
				}
			}
			batch = new Batch(payload.batchId(), level, new ArrayList<>(), new int[4], refusal != null);
			PENDING.put(id, batch);
			if (refusal != null) {
				player.sendSystemMessage(Component.literal("Ghost Mason: " + refusal).withStyle(ChatFormatting.RED));
			}
		}
		if (batch.rejected()) {
			if (payload.last()) {
				PENDING.remove(id);
			}
			return;
		}

		BlockPos origin = player.blockPosition();
		double maxDistSqr = (double) config.maxDistance * config.maxDistance;
		// Free placement also skips neighbour shape updates, so nothing nearby is knocked loose.
		int flags = payload.free() ? Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE : Block.UPDATE_CLIENTS;
		for (ApplyChunkPayload.Entry e : payload.entries()) {
			if (batch.previous().size() >= config.maxBlocksPerApply) {
				batch.counts()[3]++;
				continue;
			}
			BlockPos pos = e.pos();
			if (pos.distSqr(origin) > maxDistSqr || !level.isLoaded(pos)) {
				batch.counts()[2]++;
				continue;
			}
			BlockState before = level.getBlockState(pos);
			if (before == e.state()) {
				continue;
			}
			// Chests, furnaces etc. are kept unless the player asked to replace them; then they
			// are broken like a player would, so the container and its contents drop as items.
			if (level.getBlockEntity(pos) != null) {
				if (!payload.replaceContainers()) {
					batch.counts()[1]++;
					continue;
				}
				level.destroyBlock(pos, true);
			}
			if (level.setBlock(pos, e.state(), flags)) {
				batch.previous().add(new ApplyChunkPayload.Entry(pos.immutable(), before));
				batch.counts()[0]++;
			}
		}

		if (payload.last()) {
			// Blocks are placed without shape updates so they do not pop off mid-build. Now that
			// everything is in place, let panes, fences, walls and stairs connect to their neighbours.
			// Free placement skips this: the client already sent connected shapes, and reshaping
			// would pop off blocks the player chose to place without support.
			for (ApplyChunkPayload.Entry placed : payload.free() ? List.<ApplyChunkPayload.Entry>of() : batch.previous()) {
				BlockState now = level.getBlockState(placed.pos());
				BlockState shaped = Block.updateFromNeighbourShapes(now, level, placed.pos());
				if (shaped != now && !shaped.isAir() && shaped.getBlock() == now.getBlock()) {
					level.setBlock(placed.pos(), shaped, Block.UPDATE_CLIENTS);
				}
			}

			PENDING.remove(id);
			LAST_PLACED.put(id, System.currentTimeMillis());
			REDO.remove(id); // a new placement makes the redo history meaningless
			if (!batch.previous().isEmpty()) {
				push(HISTORY, id, batch);
			}
			StringBuilder msg = new StringBuilder("Ghost Mason placed ").append(batch.counts()[0]).append(" blocks.");
			if (batch.counts()[1] > 0) {
				msg.append(" Kept ").append(batch.counts()[1]).append(" containers that were in the way.");
			}
			if (batch.counts()[2] > 0) {
				msg.append(" Skipped ").append(batch.counts()[2]).append(" too far away or unloaded.");
			}
			if (batch.counts()[3] > 0) {
				msg.append(" Stopped at this server's limit of ").append(config.maxBlocksPerApply)
						.append(" blocks; ").append(batch.counts()[3]).append(" were not placed (run /gm apply again for the rest).");
			}
			msg.append(" /gm undo reverts it.");
			var line = Component.literal(msg.toString()).withStyle(ChatFormatting.GREEN);
			if (batch.counts()[1] > 0) {
				line.append(Component.literal(" [Replace them]").withStyle(style -> style
						.withColor(ChatFormatting.GOLD)
						.withUnderlined(true)
						.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("gm apply replace"))
						.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(
								"Breaks those containers so they and their contents drop as items, then places the plan there.")))));
			}
			player.sendSystemMessage(line);
			GhostMason.LOGGER.info("{} placed {} blocks", player.getName().getString(), batch.counts()[0]);
		}
	}

	/** Undo moves a batch from the undo history to the redo history; redo moves it back. */
	public static void onHistory(HistoryPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		if (!allowed(player)) {
			player.sendSystemMessage(Component.literal("Ghost Mason: " + permissionHint()).withStyle(ChatFormatting.RED));
			return;
		}
		UUID id = player.getUUID();
		Deque<Batch> from = (payload.redo() ? REDO : HISTORY).computeIfAbsent(id, k -> new ArrayDeque<>());
		Batch batch = from.poll();
		if (batch == null) {
			player.sendSystemMessage(Component.literal("Ghost Mason: nothing to " + (payload.redo() ? "redo" : "undo") + ".")
					.withStyle(ChatFormatting.YELLOW));
			return;
		}

		// Swap each recorded state with what is in the world now, so the batch can be reversed again.
		List<ApplyChunkPayload.Entry> recorded = batch.previous();
		List<ApplyChunkPayload.Entry> swapped = new ArrayList<>(recorded.size());
		int changed = 0;
		for (int i = recorded.size() - 1; i >= 0; i--) {
			ApplyChunkPayload.Entry e = recorded.get(i);
			if (!batch.level().isLoaded(e.pos())) {
				continue;
			}
			BlockState now = batch.level().getBlockState(e.pos());
			if (batch.level().setBlock(e.pos(), e.state(), Block.UPDATE_CLIENTS)) {
				changed++;
			}
			swapped.add(new ApplyChunkPayload.Entry(e.pos(), now));
		}
		push(payload.redo() ? HISTORY : REDO, id, new Batch(batch.id(), batch.level(), swapped, batch.counts(), false));
		player.sendSystemMessage(Component.literal("Ghost Mason " + (payload.redo() ? "redid " : "undid ") + changed
				+ " block changes. " + (payload.redo() ? "/gm undo reverts it." : "/gm redo puts it back.")).withStyle(ChatFormatting.GREEN));
	}

	private static void push(Map<UUID, Deque<Batch>> stacks, UUID id, Batch batch) {
		Deque<Batch> stack = stacks.computeIfAbsent(id, k -> new ArrayDeque<>());
		stack.push(batch);
		while (stack.size() > Math.max(0, config.undoDepth)) {
			stack.removeLast();
		}
	}
}
