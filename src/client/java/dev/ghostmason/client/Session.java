package dev.ghostmason.client;

import dev.ghostmason.core.GhostMasonConfig;
import dev.ghostmason.core.RegionSnapshot;
import dev.ghostmason.core.ai.ChatMessage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Client-side state for the current selection and plan. Touch only from the client thread. */
final class Session {
	static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("ghostmason.json");
	static GhostMasonConfig config = GhostMasonConfig.load(CONFIG_PATH);

	static BlockPos pos1;
	static BlockPos pos2;

	/** The region the current conversation is about, as it was when first scanned. */
	static RegionSnapshot snapshot;
	static BlockPos origin;
	static final List<ChatMessage> conversation = new ArrayList<>();

	/** Every design the AI produced in this conversation: the full region after its script ran. */
	static final List<String[]> versions = new ArrayList<>();
	/** Region indices that any version changed. Only these are compared with the world. */
	static final BitSet owned = new BitSet();

	static List<Ghost> ghosts = List.of();
	/** Ghosts to draw as block models this frame, chosen by the tick handler. */
	static List<Ghost> visible = List.of();
	/** Ghosts not yet matched by the world, updated every tick. */
	static int remaining;

	enum DiffKind { ADDED, REMOVED, CHANGED }

	record DiffEntry(BlockPos pos, DiffKind kind) {
	}

	/** Differences between the latest design and the one before it (or the original terrain). */
	static List<DiffEntry> diff = List.of();
	static boolean diffOn;

	/** What the placement check fixed or left out in the latest design. */
	static PlanValidator.Report lastValidation;

	static boolean busy;
	/** The request in progress, so /gm cancel can stop it. */
	static DesignProgress current;

	static final ExecutorService AI_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Ghost Mason AI");
		t.setDaemon(true);
		return t;
	});

	static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Ghost Mason timer");
		t.setDaemon(true);
		return t;
	});

	private Session() {
	}

	static boolean hasSelection() {
		return pos1 != null && pos2 != null;
	}

	static BlockPos selectionMin() {
		return new BlockPos(Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()));
	}

	static BlockPos selectionMax() {
		return new BlockPos(Math.max(pos1.getX(), pos2.getX()), Math.max(pos1.getY(), pos2.getY()), Math.max(pos1.getZ(), pos2.getZ()));
	}

	static long selectionVolume() {
		BlockPos min = selectionMin();
		BlockPos max = selectionMax();
		return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
	}

	/** The latest design, or null when there is none. */
	static String[] latest() {
		return versions.isEmpty() ? null : versions.getLast();
	}

	static void clearPlan() {
		snapshot = null;
		origin = null;
		conversation.clear();
		versions.clear();
		owned.clear();
		ghosts = List.of();
		visible = List.of();
		diff = List.of();
		remaining = 0;
		turns = 0;
		layer = null;
		lastValidation = null;
		GhostBlockRenderer.clearCache();
	}

	// --- Placement transform: designs stay in the AI's frame; these place them in the world ---

	/** Quarter turns clockwise (seen from above) applied to the design, 0 to 3. */
	static int turns;
	/** Ghosts shown for this world Y only, or null for all layers. */
	static Integer layer;

	/** Region-relative x/z after rotation, for a design position. */
	static int[] rotatedXZ(int x, int z) {
		int sx = snapshot.sizeX;
		int sz = snapshot.sizeZ;
		return switch (turns & 3) {
			case 1 -> new int[]{sz - 1 - z, x};
			case 2 -> new int[]{sx - 1 - x, sz - 1 - z};
			case 3 -> new int[]{z, sx - 1 - x};
			default -> new int[]{x, z};
		};
	}

	/** Footprint size along x and z after rotation. */
	static int placedSizeX() {
		return (turns & 1) == 0 ? snapshot.sizeX : snapshot.sizeZ;
	}

	static int placedSizeZ() {
		return (turns & 1) == 0 ? snapshot.sizeZ : snapshot.sizeX;
	}

	/** World position of a design index. */
	static BlockPos worldPos(int index) {
		int[] xz = rotatedXZ(snapshot.xOf(index), snapshot.zOf(index));
		return origin.offset(xz[0], snapshot.yOf(index), xz[1]);
	}

	static net.minecraft.world.level.block.Rotation rotation() {
		return switch (turns & 3) {
			case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
			case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
			case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
			default -> net.minecraft.world.level.block.Rotation.NONE;
		};
	}

	/** Makes the selection outline follow the placed design. */
	static void selectionFollowsPlan() {
		pos1 = origin;
		pos2 = origin.offset(placedSizeX() - 1, snapshot.sizeY - 1, placedSizeZ() - 1);
	}

	static boolean freePlacement() {
		return "free".equalsIgnoreCase(config.placement);
	}

	static void reloadConfig() {
		config = GhostMasonConfig.load(CONFIG_PATH);
	}

	static void saveConfig() {
		config.save(CONFIG_PATH);
	}
}
