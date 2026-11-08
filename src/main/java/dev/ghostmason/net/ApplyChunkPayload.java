package dev.ghostmason.net;

import dev.ghostmason.GhostMason;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Client asks the server to place part of a plan. Plans are split over several payloads to
 * stay under the packet size limit; all parts share a batch id and the final one has last=true.
 */
/**
 * @param free              place without support checks or neighbour shape updates
 * @param replaceContainers break chests, furnaces etc. in the way (contents drop as items) instead of keeping them
 */
public record ApplyChunkPayload(int batchId, boolean last, boolean free, boolean replaceContainers, List<Entry> entries)
		implements CustomPacketPayload {
	/** Entries per payload: ~12 bytes each keeps a payload well under the 32 KiB limit. */
	public static final int MAX_ENTRIES = 1500;

	public record Entry(BlockPos pos, BlockState state) {
	}

	public static final Type<ApplyChunkPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(GhostMason.MOD_ID, "apply_chunk"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ApplyChunkPayload> CODEC =
			StreamCodec.of(ApplyChunkPayload::write, ApplyChunkPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, ApplyChunkPayload payload) {
		buf.writeVarInt(payload.batchId);
		buf.writeBoolean(payload.last);
		buf.writeBoolean(payload.free);
		buf.writeBoolean(payload.replaceContainers);
		buf.writeVarInt(payload.entries.size());
		for (Entry e : payload.entries) {
			buf.writeBlockPos(e.pos);
			buf.writeVarInt(Block.getId(e.state));
		}
	}

	private static ApplyChunkPayload read(RegistryFriendlyByteBuf buf) {
		int batchId = buf.readVarInt();
		boolean last = buf.readBoolean();
		boolean free = buf.readBoolean();
		boolean replaceContainers = buf.readBoolean();
		int size = buf.readVarInt();
		if (size < 0 || size > MAX_ENTRIES) {
			throw new IllegalArgumentException("Ghost Mason apply payload too large: " + size);
		}
		List<Entry> entries = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			BlockPos pos = buf.readBlockPos();
			BlockState state = Block.stateById(buf.readVarInt());
			entries.add(new Entry(pos, state));
		}
		return new ApplyChunkPayload(batchId, last, free, replaceContainers, entries);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
