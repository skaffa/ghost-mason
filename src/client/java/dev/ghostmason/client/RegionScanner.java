package dev.ghostmason.client;

import dev.ghostmason.core.RegionSnapshot;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;

/** Reads a box of the client's world into a {@link RegionSnapshot}. */
final class RegionScanner {
	private RegionScanner() {
	}

	/** @throws IllegalStateException if part of the box is not loaded */
	static RegionSnapshot scan(Level level, BlockPos min, BlockPos max) {
		int sx = max.getX() - min.getX() + 1;
		int sy = max.getY() - min.getY() + 1;
		int sz = max.getZ() - min.getZ() + 1;
		String[] states = new String[sx * sy * sz];
		// BlockState instances are shared, so cache their serialized form.
		Map<BlockState, String> names = new IdentityHashMap<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					p.set(min.getX() + x, min.getY() + y, min.getZ() + z);
					if (!level.isLoaded(p)) {
						throw new IllegalStateException("Part of the selection is not loaded. Move closer.");
					}
					BlockState state = level.getBlockState(p);
					states[i++] = names.computeIfAbsent(state, BlockStateParser::serialize);
				}
			}
		}
		return new RegionSnapshot(sx, sy, sz, states);
	}
}
