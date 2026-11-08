package dev.ghostmason.net;

import dev.ghostmason.GhostMason;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client asks the server to undo (redo=false) or redo (redo=true) its last placement. */
public record HistoryPayload(boolean redo) implements CustomPacketPayload {
	public static final Type<HistoryPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(GhostMason.MOD_ID, "history"));

	public static final StreamCodec<RegistryFriendlyByteBuf, HistoryPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBoolean(payload.redo),
			buf -> new HistoryPayload(buf.readBoolean()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
