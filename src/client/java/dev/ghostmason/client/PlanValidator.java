package dev.ghostmason.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks every planned block with Minecraft's own survival rules (the same check that makes a
 * torch pop off a glass pane), against the planned neighbours rather than today's world.
 * Wall-mounted blocks get a chance to turn towards a surface that can hold them; anything
 * that still cannot stay is left out of the plan.
 */
final class PlanValidator {
	/**
	 * @param reoriented blocks that were turned to face a supporting surface
	 * @param dropped    block id to number of blocks left out
	 */
	record Report(int reoriented, Map<String, Integer> dropped) {
		boolean isEmpty() {
			return reoriented == 0 && dropped.isEmpty();
		}
	}

	private static final int MAX_PASSES = 3;

	private PlanValidator() {
	}

	/** Fixes or removes unsupported blocks in {@code planned}, in place. */
	static Report validate(ClientLevel level, Map<BlockPos, BlockState> planned) {
		LevelReader reader = overlay(level, planned);
		int reoriented = 0;
		Map<String, Integer> dropped = new TreeMap<>();

		// Removing one block can strand another (a lantern hanging from a dropped torch...), so repeat.
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			boolean changed = false;
			for (BlockPos pos : new ArrayList<>(planned.keySet())) {
				BlockState state = planned.get(pos);
				if (state.isAir() || survives(state, reader, pos)) {
					continue;
				}
				BlockState turned = reorient(state, reader, pos);
				if (turned != null) {
					planned.put(pos, turned);
					reoriented++;
				} else {
					planned.remove(pos);
					dropped.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
				}
				changed = true;
			}
			if (!changed) {
				break;
			}
		}
		return new Report(reoriented, dropped);
	}

	private static boolean survives(BlockState state, LevelReader reader, BlockPos pos) {
		try {
			return state.canSurvive(reader, pos);
		} catch (RuntimeException e) {
			return true; // a block whose check needs more than our overlay offers: trust the AI
		}
	}

	/** Tries the other directions for blocks that attach by facing; null if none can stay. */
	private static BlockState reorient(BlockState state, LevelReader reader, BlockPos pos) {
		List<BlockState> options = new ArrayList<>();
		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				options.add(state.setValue(BlockStateProperties.HORIZONTAL_FACING, d));
			}
		} else if (state.hasProperty(BlockStateProperties.FACING)) {
			for (Direction d : Direction.values()) {
				options.add(state.setValue(BlockStateProperties.FACING, d));
			}
		}
		if (state.hasProperty(BlockStateProperties.HANGING)) {
			options.add(state.setValue(BlockStateProperties.HANGING, !state.getValue(BlockStateProperties.HANGING)));
		}
		for (BlockState option : options) {
			if (option != state && survives(option, reader, pos)) {
				return option;
			}
		}
		return null;
	}

	/**
	 * A read-only view of the level where planned positions show their planned block.
	 * Block and fluid lookups use the plan; helper methods run on this view; the remaining
	 * low-level methods are answered by the real level.
	 */
	static LevelReader overlay(ClientLevel level, Map<BlockPos, BlockState> planned) {
		return (LevelReader) Proxy.newProxyInstance(LevelReader.class.getClassLoader(), new Class<?>[]{LevelReader.class},
				(proxy, method, args) -> {
					if (args != null && args.length == 1 && args[0] instanceof BlockPos p) {
						if (method.getName().equals("getBlockState")) {
							BlockState s = planned.get(p);
							return s != null ? s : level.getBlockState(p);
						}
						if (method.getName().equals("getFluidState")) {
							BlockState s = planned.get(p);
							return s != null ? s.getFluidState() : level.getFluidState(p);
						}
					}
					// Helper methods such as isEmptyBlock() are built on getBlockState(); run them on
					// this view so they see the plan too, not the real level's own copy.
					if (method.isDefault()) {
						return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
					}
					try {
						return method.invoke(level, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}
}
