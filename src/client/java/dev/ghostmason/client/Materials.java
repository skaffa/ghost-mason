package dev.ghostmason.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The blocks still needed to finish the plan by hand, compared with the player's inventory. */
final class Materials {
	private static final int SHOWN = 10;

	private Materials() {
	}

	static void report() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		Map<Item, Integer> needed = new HashMap<>();
		for (Ghost g : Session.ghosts) {
			if (g.removal() || Planner.built(level.getBlockState(g.pos()), g)) {
				continue;
			}
			BlockState t = g.target();
			// Two-block items (doors, tall plants, beds) count once.
			if (t.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
					&& t.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
				continue;
			}
			if (t.hasProperty(BlockStateProperties.BED_PART) && t.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
				continue;
			}
			Item item = t.getBlock().asItem();
			if (item == Items.AIR) {
				continue; // water, fire and similar have no item
			}
			int count = t.hasProperty(BlockStateProperties.SLAB_TYPE) && t.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
			needed.merge(item, count, Integer::sum);
		}
		if (needed.isEmpty()) {
			Msg.good("Nothing left to place by hand.");
			return;
		}

		Map<Item, Integer> have = new HashMap<>();
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				have.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}

		List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(needed.entrySet());
		sorted.sort((a, b) -> b.getValue() - a.getValue());
		Msg.info("Blocks still needed (you have):");
		for (Map.Entry<Item, Integer> e : sorted.subList(0, Math.min(SHOWN, sorted.size()))) {
			int got = have.getOrDefault(e.getKey(), 0);
			boolean enough = got >= e.getValue();
			MutableComponent line = Component.literal("  " + e.getValue() + " x ").withStyle(ChatFormatting.WHITE)
					.append(new ItemStack(e.getKey()).getHoverName().copy().withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" (" + got + ")").withStyle(enough ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
			Msg.send(line);
		}
		if (sorted.size() > SHOWN) {
			int rest = sorted.subList(SHOWN, sorted.size()).stream().mapToInt(Map.Entry::getValue).sum();
			Msg.info("  ...and " + rest + " more of " + (sorted.size() - SHOWN) + " other kinds.");
		}
	}
}
