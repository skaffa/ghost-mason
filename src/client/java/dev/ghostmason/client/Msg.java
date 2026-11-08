package dev.ghostmason.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Chat output, prefixed so players can tell where it comes from. Call on the client thread. */
final class Msg {
	private Msg() {
	}

	static void info(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	static void good(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.GREEN));
	}

	static void warn(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.YELLOW));
	}

	static void error(String text) {
		send(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	static void send(Component body) {
		MutableComponent line = Component.literal("[Ghost Mason] ").withStyle(ChatFormatting.DARK_AQUA).append(body);
		if (Minecraft.getInstance().player != null) {
			Minecraft.getInstance().player.sendSystemMessage(line);
		}
	}
}
