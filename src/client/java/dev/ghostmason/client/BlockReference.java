package dev.ghostmason.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything block-related that the AI needs, read from the running game instead of written
 * by hand, so new Minecraft versions and modded blocks work without updating Ghost Mason.
 */
final class BlockReference {
	/** Technical blocks a builder never wants. */
	private static final Set<String> EXCLUDED = Set.of(
			"air", "cave_air", "void_air", "structure_block", "structure_void", "jigsaw", "command_block",
			"chain_command_block", "repeating_command_block", "barrier", "light", "moving_piston",
			"piston_head", "end_portal", "end_gateway", "nether_portal", "bubble_column", "frosted_ice",
			"test_block", "test_instance_block");

	private static String cached;

	private BlockReference() {
	}

	/**
	 * Every placeable block, grouped by its property set, e.g.
	 * {@code [facing=north|south|west|east half=top|bottom ...] oak_stairs, spruce_stairs, ...}.
	 */
	static String text() {
		if (cached == null) {
			Map<String, List<String>> groups = new TreeMap<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				Identifier id = BuiltInRegistries.BLOCK.getKey(block);
				if (id.getNamespace().equals("minecraft") && EXCLUDED.contains(id.getPath())) {
					continue;
				}
				String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
				StringBuilder signature = new StringBuilder();
				for (Property<?> p : block.getStateDefinition().getProperties()) {
					if (!signature.isEmpty()) {
						signature.append(' ');
					}
					signature.append(p.getName()).append('=').append(values(p));
				}
				groups.computeIfAbsent(signature.toString(), k -> new ArrayList<>()).add(name);
			}
			StringBuilder out = new StringBuilder();
			groups.forEach((signature, names) -> out.append('[')
					.append(signature.isEmpty() ? "no properties" : signature)
					.append("] ").append(String.join(", ", names)).append('\n'));
			cached = out.toString();
		}
		return cached;
	}

	private static <T extends Comparable<T>> String values(Property<T> property) {
		StringBuilder sb = new StringBuilder();
		for (T value : property.getPossibleValues()) {
			if (!sb.isEmpty()) {
				sb.append('|');
			}
			sb.append(property.getName(value));
		}
		return sb.toString();
	}

	/**
	 * Parses a block state the forgiving way: a wrong or unknown property is ignored instead of
	 * throwing the whole block away, as long as the block id itself exists.
	 */
	static BlockState parse(String text) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
		} catch (CommandSyntaxException strictFailed) {
			// fall through to the lenient path
		}
		String idPart = text;
		String props = "";
		int bracket = text.indexOf('[');
		if (bracket >= 0) {
			idPart = text.substring(0, bracket);
			int end = text.lastIndexOf(']');
			props = text.substring(bracket + 1, end > bracket ? end : text.length());
		}
		Identifier id = Identifier.tryParse(idPart.trim());
		if (id == null) {
			return null;
		}
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
		if (block.isEmpty()) {
			return null;
		}
		BlockState state = block.get().defaultBlockState();
		for (String pair : props.split(",")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			Property<?> property = block.get().getStateDefinition().getProperty(pair.substring(0, eq).trim());
			if (property != null) {
				state = withValue(state, property, pair.substring(eq + 1).trim());
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}
}
