package dev.ghostmason.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One planned block, drawn until the world matches it.
 *
 * @param removal  the plan wants air here
 * @param tints    per-tint-layer colours (grass, leaves, water...) for the block model
 * @param modelled the block has a normal block model; otherwise it is drawn as a tinted box
 * @param boxColor RGB used for the box fallback
 */
public record Ghost(BlockPos pos, BlockState target, boolean removal, int[] tints, boolean modelled, int boxColor) {
}
