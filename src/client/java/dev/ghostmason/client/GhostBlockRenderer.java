package dev.ghostmason.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws planned blocks as see-through copies of the real block model. The tick handler
 * decides which ghosts are visible; this only submits them to the frame.
 */
final class GhostBlockRenderer {
	/** Multiplied into every vertex: translucent with a faint cool tint so ghosts read as ghosts. */
	private static final int GHOST_TINT = 0x9CDCEBFF;
	/** Slightly smaller than a block so a ghost replacing an existing block does not flicker. */
	private static final float SCALE = 0.96f;

	private static final Map<BlockState, List<BlockStateModelPart>> PARTS = new IdentityHashMap<>();

	private GhostBlockRenderer() {
	}

	static void clearCache() {
		PARTS.clear();
	}

	/** True when the block has quads to draw (chests, signs, liquids etc. do not). */
	static boolean hasModel(BlockState state) {
		return !parts(state).isEmpty();
	}

	private static List<BlockStateModelPart> parts(BlockState state) {
		return PARTS.computeIfAbsent(state, s -> {
			BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
			List<BlockStateModelPart> list = new ArrayList<>();
			models.get(s).collectParts(RandomSource.create(42L), list);
			return list;
		});
	}

	static void render(LevelRenderContext context) {
		List<Ghost> visible = Session.visible;
		if (visible.isEmpty()) {
			return;
		}
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		PoseStack pose = context.poseStack();
		SubmitNodeCollector collector = context.submitNodeCollector();
		RenderType type = RenderTypes.translucentMovingBlock();

		for (Ghost g : visible) {
			List<BlockStateModelPart> parts = parts(g.target());
			if (parts.isEmpty()) {
				continue;
			}
			pose.pushPose();
			pose.translate(g.pos().getX() - cam.x + 0.5, g.pos().getY() - cam.y + 0.5, g.pos().getZ() - cam.z + 0.5);
			pose.scale(SCALE, SCALE, SCALE);
			pose.translate(-0.5, -0.5, -0.5);
			collector.submitBlockModel(pose, type, parts, g.tints(), LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, GHOST_TINT);
			pose.popPose();
		}
	}
}
