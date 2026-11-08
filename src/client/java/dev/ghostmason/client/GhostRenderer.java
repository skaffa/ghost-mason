package dev.ghostmason.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs each client tick: works out which ghosts still need building, hands the visible ones
 * to {@link GhostBlockRenderer}, and draws boxes (selection, removals, diff overlay) with the
 * game's gizmo system, inside its per-tick gizmo collection.
 */
final class GhostRenderer {
	private static final int SELECTION_COLOR = 0xFFFFD040;
	private static final int REMOVE_STROKE = 0xFFFF3B30;
	private static final int REMOVE_FILL = 0x30FF2020;
	private static final double INSET = 0.03;

	private GhostRenderer() {
	}

	static void tick(Minecraft client) {
		Level level = client.level;
		if (level == null || client.player == null) {
			Session.visible = List.of();
			return;
		}

		if (Session.pos1 != null && Session.pos2 != null) {
			BlockPos min = Session.selectionMin();
			BlockPos max = Session.selectionMax();
			AABB box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
			Gizmos.cuboid(box, GizmoStyle.stroke(SELECTION_COLOR, 2.0f));
		} else if (Session.pos1 != null) {
			Gizmos.cuboid(Session.pos1, GizmoStyle.stroke(SELECTION_COLOR, 2.0f));
		}

		Vec3 eye = client.player.getEyePosition();
		double maxDistSqr = (double) Session.config.ghostRenderDistance * Session.config.ghostRenderDistance;
		int cap = Session.config.maxGhostsDrawn;
		int remaining = 0;
		List<Ghost> visible = new ArrayList<>();

		// Unbuilt ghosts in range; when there are more than the cap, the nearest ones win.
		List<Ghost> inRange = new ArrayList<>();
		for (Ghost g : Session.ghosts) {
			if (Planner.built(level.getBlockState(g.pos()), g)) {
				continue;
			}
			remaining++;
			if (Session.layer != null && g.pos().getY() != Session.layer) {
				continue;
			}
			if (g.pos().distToCenterSqr(eye) <= maxDistSqr) {
				inRange.add(g);
			}
		}
		if (inRange.size() > cap) {
			inRange.sort(java.util.Comparator.comparingDouble(g -> g.pos().distToCenterSqr(eye)));
			inRange = inRange.subList(0, cap);
		}

		for (Ghost g : inRange) {
			if (g.removal()) {
				Gizmos.cuboid(inset(g.pos()), GizmoStyle.strokeAndFill(REMOVE_STROKE, 1.5f, REMOVE_FILL));
			} else if (g.modelled()) {
				visible.add(g);
			} else {
				int rgb = g.boxColor();
				Gizmos.cuboid(inset(g.pos()), GizmoStyle.strokeAndFill(0xE0000000 | rgb, 1.0f, 0x70000000 | rgb));
			}
		}
		Session.visible = visible;
		Session.remaining = remaining;

		if (Session.diffOn) {
			int shown = 0;
			for (Session.DiffEntry d : Session.diff) {
				if (shown >= cap) {
					break;
				}
				if (d.pos().distToCenterSqr(eye) > maxDistSqr) {
					continue;
				}
				shown++;
				int color = switch (d.kind()) {
					case ADDED -> 0x40D86B;
					case REMOVED -> 0xFF3B30;
					case CHANGED -> 0xFFB020;
				};
				AABB box = new AABB(d.pos().getX() - 0.01, d.pos().getY() - 0.01, d.pos().getZ() - 0.01,
						d.pos().getX() + 1.01, d.pos().getY() + 1.01, d.pos().getZ() + 1.01);
				Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xFF000000 | color, 2.0f, 0x38000000 | color));
			}
		}
	}

	private static AABB inset(BlockPos p) {
		return new AABB(p.getX() + INSET, p.getY() + INSET, p.getZ() + INSET,
				p.getX() + 1 - INSET, p.getY() + 1 - INSET, p.getZ() + 1 - INSET);
	}
}
