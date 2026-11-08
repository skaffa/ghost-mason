package dev.ghostmason.client;

import dev.ghostmason.core.BuildScript;
import dev.ghostmason.core.PromptBuilder;
import dev.ghostmason.core.RegionSnapshot;
import dev.ghostmason.core.ScriptException;
import dev.ghostmason.core.ai.AiException;
import dev.ghostmason.core.ai.AiProvider;
import dev.ghostmason.core.ai.CancelledException;
import dev.ghostmason.core.ai.ChatMessage;
import dev.ghostmason.core.ai.MissingKeyException;
import dev.ghostmason.net.ApplyChunkPayload;
import dev.ghostmason.net.HistoryPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns requests into AI calls, AI answers into ghosts, and ghosts into placement payloads.
 * Designs are kept in the AI's own frame; move/rotate only change how they are placed.
 */
final class Planner {
	private static final int[] NO_TINTS = new int[0];

	private Planner() {
	}

	// --- Asking the AI -------------------------------------------------------------------------

	/** Starts a new plan for the current selection. */
	static void build(String request) {
		Minecraft client = Minecraft.getInstance();
		if (Session.busy) {
			Msg.warn("Still working on the last request.");
			return;
		}
		if (!Session.hasSelection()) {
			Msg.error("Select a region first: look at a block and run /gm pos1, then /gm pos2.");
			return;
		}
		long volume = Session.selectionVolume();
		if (volume > Session.config.maxRegionVolume) {
			Msg.error("Selection is " + volume + " blocks; the limit is " + Session.config.maxRegionVolume
					+ ". Raise it with /gm config maxvolume <blocks>, or select less.");
			return;
		}

		RegionSnapshot snapshot;
		try {
			snapshot = RegionScanner.scan(client.level, Session.selectionMin(), Session.selectionMax());
		} catch (IllegalStateException e) {
			Msg.error(e.getMessage());
			return;
		}

		Session.clearPlan();
		Session.snapshot = snapshot;
		Session.origin = Session.selectionMin();
		Session.conversation.add(ChatMessage.user(PromptBuilder.initialRequest(snapshot, request)));
		ask(false);
	}

	/** Asks the AI to change the current plan. */
	static void refine(String request) {
		if (!readyToRefine()) {
			return;
		}
		Session.conversation.add(ChatMessage.user(PromptBuilder.revisionRequest(request)));
		ask(false);
	}

	private static boolean readyToRefine() {
		if (Session.busy) {
			Msg.warn("Still working on the last request.");
			return false;
		}
		if (Session.snapshot == null || Session.conversation.isEmpty()) {
			Msg.error("No plan to refine. Start one with /gm build <description>.");
			return false;
		}
		if (Session.conversation.getLast().role().equals("user")) {
			// The previous request failed; replace it instead of sending two user turns in a row.
			Session.conversation.removeLast();
			if (Session.conversation.isEmpty()) {
				Msg.error("The first request failed. Start again with /gm build <description>.");
				return false;
			}
		}
		return true;
	}

	/** Goes back to the previous version of the design. */
	static void back() {
		if (Session.busy) {
			Msg.warn("Still working on the last request.");
			return;
		}
		if (Session.versions.size() < 2) {
			Msg.error("There is no earlier version to go back to.");
			return;
		}
		Session.versions.removeLast();
		// Drop the matching request and answer, so the next refinement continues from here.
		if (!Session.conversation.isEmpty() && Session.conversation.getLast().role().equals("user")) {
			Session.conversation.removeLast();
		}
		if (!Session.conversation.isEmpty()) {
			Session.conversation.removeLast();
		}
		if (!Session.conversation.isEmpty()) {
			Session.conversation.removeLast();
		}
		refreshPlan();
		Msg.good("Back to version " + Session.versions.size() + ". " + countsLine());
	}

	private static void ask(boolean review) {
		ask(review, false);
	}

	/**
	 * @param quick the player cut the thinking short: answer with the provider's least thinking,
	 *              and do not offer another quick retry if this one is cancelled too
	 */
	private static void ask(boolean review, boolean quick) {
		AiProvider provider;
		try {
			provider = Session.config.createProvider(quick);
		} catch (MissingKeyException e) {
			KeyLink.begin(e.provider(), () -> resumeAfterLink(review));
			return;
		} catch (AiException e) {
			Msg.error(e.getMessage());
			return;
		}

		String system = PromptBuilder.systemPrompt(minecraftVersion(),
				Session.config.blockReference ? BlockReference.text() : "",
				dev.ghostmason.core.CustomPrompt.load(Session.CONFIG_PATH.resolveSibling("ghostmason-prompt.txt")));
		List<ChatMessage> messages = List.copyOf(Session.conversation);
		RegionSnapshot snapshot = Session.snapshot;
		int maxChanges = Session.config.maxChanges;
		DesignProgress progress = new DesignProgress(Session.config.verbosity, Session.config.showTokens, snapshot);

		Session.busy = true;
		Session.current = progress;
		Msg.info(provider.displayName() + (quick ? " is writing the build now..." : review ? " is reviewing its design..." : " is designing...")
				+ " (/gm cancel stops it)");
		progress.start();

		CompletableFuture.supplyAsync(() -> {
			try {
				String reply = provider.complete(system, messages, progress.wantsThinking(), progress);
				if (review && BuildScript.isApproval(reply)) {
					return Outcome.approval(reply);
				}
				return Outcome.design(reply, BuildScript.run(reply, snapshot, maxChanges));
			} catch (CancelledException e) {
				return cancelled(progress, review, quick, snapshot, maxChanges);
			} catch (MissingKeyException e) {
				return Outcome.link(e.getMessage(), e.provider());
			} catch (AiException | ScriptException e) {
				return Outcome.error(e.getMessage());
			} catch (RuntimeException e) {
				return Outcome.error("Unexpected error: " + e);
			} finally {
				progress.stop();
			}
		}, Session.AI_EXECUTOR).thenAccept(outcome -> Minecraft.getInstance().execute(() -> finish(outcome, snapshot, review)));
	}

	/**
	 * What a cancelled request turns into. Without keep: nothing. With keep: the build steps
	 * already written, or, if the AI was still thinking, a quick follow-up asking for the build now.
	 */
	private static Outcome cancelled(DesignProgress progress, boolean review, boolean quick, RegionSnapshot snapshot, int maxChanges) {
		if (review) {
			return Outcome.error("Review cancelled. The design stays as it was.");
		}
		if (!progress.keepOnCancel()) {
			return Outcome.error("Cancelled.");
		}
		String salvaged = BuildScript.salvage(progress.answerSoFar());
		if (salvaged != null) {
			try {
				return Outcome.design(salvaged, BuildScript.run(salvaged, snapshot, maxChanges));
			} catch (ScriptException e) {
				return Outcome.error("Cancelled. The partial design could not be used: " + e.getMessage());
			}
		}
		if (quick) {
			return Outcome.error("Cancelled. No build steps were written yet, so there is nothing to keep.");
		}
		return Outcome.rush(progress.reasoningSoFar());
	}

	/** Stops the request in progress; with keep, what was designed so far is used. */
	static void cancel(boolean keep) {
		DesignProgress current = Session.current;
		if (!Session.busy || current == null) {
			Msg.info("Nothing is being designed right now.");
			return;
		}
		current.cancel(keep);
		Msg.info(keep ? "Stopping; keeping what has been designed so far..." : "Stopping...");
	}

	/** Re-sends the request that was waiting for a provider, if it is still current. */
	private static void resumeAfterLink(boolean review) {
		if (!Session.busy && Session.snapshot != null && !Session.conversation.isEmpty()
				&& Session.conversation.getLast().role().equals("user")) {
			ask(review);
		}
	}

	/**
	 * @param linkProvider set when the request failed because the provider needs connecting first
	 * @param approved     a review that found nothing to change
	 * @param rushPlan     set when the player stopped the thinking: the reasoning so far ("" if hidden)
	 */
	private record Outcome(String reply, BuildScript.Result result, String error, String linkProvider, boolean approved,
			String rushPlan) {
		static Outcome design(String reply, BuildScript.Result result) {
			return new Outcome(reply, result, null, null, false, null);
		}

		static Outcome approval(String reply) {
			return new Outcome(reply, null, null, null, true, null);
		}

		static Outcome error(String message) {
			return new Outcome(null, null, message, null, false, null);
		}

		static Outcome link(String message, String provider) {
			return new Outcome(null, null, message, provider, false, null);
		}

		static Outcome rush(String reasoningSoFar) {
			return new Outcome(null, null, null, null, false, reasoningSoFar);
		}
	}

	private static void finish(Outcome outcome, RegionSnapshot snapshot, boolean review) {
		Session.busy = false;
		Session.current = null;
		if (Session.snapshot != snapshot) {
			return; // plan was cleared or replaced while waiting
		}
		if (outcome.rushPlan() != null) {
			// Ask again with the thinking cut short: the same request, plus "build it now".
			ChatMessage request = Session.conversation.removeLast();
			Session.conversation.add(ChatMessage.user(request.content() + PromptBuilder.rushInstruction(outcome.rushPlan())));
			Msg.info("Asking for the build right away, based on the plan so far...");
			ask(false, true);
			return;
		}
		if (outcome.linkProvider() != null) {
			Msg.warn(outcome.error());
			KeyLink.begin(outcome.linkProvider(), () -> resumeAfterLink(review));
			return;
		}
		if (outcome.approved()) {
			Session.conversation.removeLast(); // the review request; the design stays as it was
			Msg.good("Review: the AI found nothing to fix.");
			return;
		}
		if (outcome.error() != null) {
			if (review) {
				Session.conversation.removeLast();
				Msg.warn("The review failed (" + outcome.error() + "). The design is unchanged.");
			} else {
				Msg.error(outcome.error());
			}
			return;
		}
		Session.conversation.add(ChatMessage.assistant(outcome.reply()));

		BuildScript.Result result = outcome.result();
		Session.versions.add(result.finalStates());
		for (int i : result.changes().keySet()) {
			Session.owned.set(i);
		}
		List<String> unknown = refreshPlan();

		if (!result.summary().isBlank()) {
			Msg.good((review ? "Review: " : "") + result.summary());
		}
		Msg.info("Plan v" + Session.versions.size() + ": " + countsLine()
				+ " /gm apply places it, /gm refine <change> revises it, /gm diff shows what changed.");
		for (String w : result.warnings().subList(0, Math.min(5, result.warnings().size()))) {
			Msg.warn(w);
		}
		if (!unknown.isEmpty()) {
			Msg.warn("Skipped unknown blocks: " + String.join(", ", unknown));
		}
		List<String> facts = validationFacts();
		for (String f : facts) {
			Msg.warn(f);
		}

		if (Session.config.review && !review) {
			Session.conversation.add(ChatMessage.user(PromptBuilder.reviewRequest(
					new RegionSnapshot(snapshot.sizeX, snapshot.sizeY, snapshot.sizeZ, result.finalStates()), facts)));
			ask(true);
		}
	}

	private static List<String> validationFacts() {
		List<String> facts = new ArrayList<>();
		PlanValidator.Report check = Session.lastValidation;
		if (check == null) {
			return facts;
		}
		if (check.reoriented() > 0) {
			facts.add("Turned " + check.reoriented() + " wall-mounted blocks to face a surface that can hold them.");
		}
		if (!check.dropped().isEmpty()) {
			StringBuilder sb = new StringBuilder("Left out blocks that would fall off: ");
			check.dropped().forEach((id, n) -> sb.append(id).append(" x").append(n).append(", "));
			sb.setLength(sb.length() - 2);
			facts.add(sb.toString());
		}
		return facts;
	}

	private static String countsLine() {
		ClientLevel level = Minecraft.getInstance().level;
		int adds = 0;
		int removes = 0;
		for (Ghost g : Session.ghosts) {
			if (!built(level.getBlockState(g.pos()), g)) {
				if (g.removal()) {
					removes++;
				} else {
					adds++;
				}
			}
		}
		return adds + " blocks to place, " + removes + " to remove.";
	}

	// --- Turning designs into ghosts ------------------------------------------------------------

	/** Rebuilds ghosts and diff after the design or its placement changed. */
	static List<String> refreshPlan() {
		List<String> unknown = rebuildGhosts();
		rebuildDiff();
		return unknown;
	}

	/**
	 * Builds the ghost list from the latest design. Every position any version touched is
	 * included, so blocks placed from an earlier version show up for removal if the latest
	 * version no longer wants them.
	 */
	private static List<String> rebuildGhosts() {
		GhostBlockRenderer.clearCache();
		ClientLevel level = Minecraft.getInstance().level;
		String[] design = Session.latest();
		Rotation rotation = Session.rotation();
		List<Ghost> ghosts = new ArrayList<>();
		Map<String, BlockState> parsed = new HashMap<>();
		List<String> unknown = new ArrayList<>();

		// Parse every planned state first, so neighbours can be looked up while shaping.
		Map<BlockPos, BlockState> planned = new HashMap<>();
		for (int i = Session.owned.nextSetBit(0); i >= 0; i = Session.owned.nextSetBit(i + 1)) {
			String stateString = design[i];
			BlockState target = parsed.computeIfAbsent(stateString, s -> {
				BlockState state = parseState(s);
				return state == null ? null : state.rotate(rotation);
			});
			if (target == null) {
				if (!unknown.contains(stateString) && unknown.size() < 5) {
					unknown.add(stateString);
				}
				continue;
			}
			planned.put(Session.worldPos(i), target);
		}

		Map<BlockPos, BlockState> shaped = new HashMap<>();
		for (Map.Entry<BlockPos, BlockState> entry : planned.entrySet()) {
			shaped.put(entry.getKey(), connect(level, entry.getKey(), entry.getValue(), planned));
		}
		Session.lastValidation = Session.freePlacement() ? null : PlanValidator.validate(level, shaped);

		for (Map.Entry<BlockPos, BlockState> entry : shaped.entrySet()) {
			BlockPos pos = entry.getKey();
			BlockState target = entry.getValue();
			if (target.isAir()) {
				ghosts.add(new Ghost(pos, target, true, NO_TINTS, false, 0));
			} else {
				int rgb = target.getMapColor(level, pos).col;
				ghosts.add(new Ghost(pos, target, false, tints(level, target, pos),
						GhostBlockRenderer.hasModel(target), rgb == 0 ? 0xFFFFFF : rgb));
			}
		}
		Session.ghosts = ghosts;
		return unknown;
	}

	/**
	 * Lets panes, fences, walls, stairs and similar blocks connect to their planned neighbours
	 * (or to the existing world outside the plan), the way they would when placed by hand.
	 * The AI rarely writes these connection properties itself.
	 */
	private static BlockState connect(ClientLevel level, BlockPos pos, BlockState state, Map<BlockPos, BlockState> planned) {
		if (state.isAir()) {
			return state;
		}
		BlockState shaped = state;
		for (Direction d : Direction.values()) {
			BlockPos neighborPos = pos.relative(d);
			BlockState neighbor = planned.get(neighborPos);
			if (neighbor == null) {
				neighbor = level.getBlockState(neighborPos);
			}
			shaped = shaped.updateShape(level, level, pos, d, neighborPos, neighbor, level.getRandom());
		}
		// Keep the AI's block if shaping would turn it into something else (e.g. an unsupported torch to air).
		return shaped.isAir() || shaped.getBlock() != state.getBlock() ? state : shaped;
	}

	/** Properties the game recalculates from neighbours, so a mismatch in them alone does not count. */
	private static final Set<String> SHAPE_PROPERTIES = Set.of("north", "east", "south", "west", "up", "down", "shape");

	/** True when the world already has what this ghost asks for. */
	static boolean built(BlockState live, Ghost ghost) {
		BlockState target = ghost.target();
		if (ghost.removal()) {
			return live.isAir();
		}
		if (live == target) {
			return true;
		}
		if (live.getBlock() != target.getBlock()) {
			return false;
		}
		for (Property<?> p : target.getProperties()) {
			if (!SHAPE_PROPERTIES.contains(p.getName()) && !live.getValue(p).equals(target.getValue(p))) {
				return false;
			}
		}
		return true;
	}

	/** Grass, leaves, water and similar blocks are coloured by biome. */
	private static int[] tints(ClientLevel level, BlockState state, BlockPos pos) {
		List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
		if (sources.isEmpty()) {
			return NO_TINTS;
		}
		int[] tints = new int[sources.size()];
		for (int i = 0; i < tints.length; i++) {
			BlockTintSource s = sources.get(i);
			int color = s == null ? 0xFFFFFF : s.colorInWorld(state, level, pos);
			tints[i] = 0xFF000000 | (color & 0xFFFFFF);
		}
		return tints;
	}

	/** Compares the latest design with the previous one, or with the original terrain for the first. */
	private static void rebuildDiff() {
		RegionSnapshot snap = Session.snapshot;
		String[] current = Session.latest();
		String[] previous = Session.versions.size() >= 2 ? Session.versions.get(Session.versions.size() - 2) : snap.copyStates();
		List<Session.DiffEntry> diff = new ArrayList<>();
		for (int i = Session.owned.nextSetBit(0); i >= 0; i = Session.owned.nextSetBit(i + 1)) {
			String a = previous[i];
			String b = current[i];
			boolean airA = RegionSnapshot.isAir(a);
			boolean airB = RegionSnapshot.isAir(b);
			if (a.equals(b) || (airA && airB)) {
				continue;
			}
			Session.DiffKind kind = airA ? Session.DiffKind.ADDED : airB ? Session.DiffKind.REMOVED : Session.DiffKind.CHANGED;
			diff.add(new Session.DiffEntry(Session.worldPos(i), kind));
		}
		Session.diff = diff;
	}

	static BlockState parseState(String state) {
		return BlockReference.parse(state);
	}

	// --- Moving, rotating, layers -------------------------------------------------------------

	/** Shifts the plan (or, without a plan, the selection). */
	static void move(int dx, int dy, int dz) {
		if (Session.busy) {
			Msg.warn("Wait until the AI is done.");
			return;
		}
		if (Session.latest() == null) {
			if (!Session.hasSelection()) {
				Msg.error("Nothing to move: no plan and no selection.");
				return;
			}
			Session.pos1 = Session.pos1.offset(dx, dy, dz);
			Session.pos2 = Session.pos2.offset(dx, dy, dz);
			Msg.info("Selection moved by " + dx + " " + dy + " " + dz + ".");
			return;
		}
		Session.origin = Session.origin.offset(dx, dy, dz);
		Session.selectionFollowsPlan();
		refreshPlan();
		Msg.good("Plan moved by " + dx + " " + dy + " " + dz + ". " + countsLine());
	}

	/** Turns the plan clockwise (seen from above) around its footprint. */
	static void rotate(int degrees) {
		if (Session.busy) {
			Msg.warn("Wait until the AI is done.");
			return;
		}
		if (Session.latest() == null) {
			Msg.error("No plan to rotate. Make one with /gm build <description>.");
			return;
		}
		Session.turns = (Session.turns + degrees / 90) & 3;
		Session.selectionFollowsPlan();
		refreshPlan();
		Msg.good("Plan rotated " + degrees + " degrees clockwise. " + countsLine());
	}

	/** Shows one layer of ghosts: a world Y, "next"/"prev" (layers with work left), or "all". */
	static void layer(String which) {
		if (Session.ghosts.isEmpty()) {
			Msg.error("No plan to show layers of.");
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		TreeSet<Integer> open = new TreeSet<>();
		for (Ghost g : Session.ghosts) {
			if (!built(level.getBlockState(g.pos()), g)) {
				open.add(g.pos().getY());
			}
		}
		Integer target;
		switch (which) {
			case "all" -> target = null;
			case "next" -> target = Session.layer == null ? open.isEmpty() ? null : open.first() : open.higher(Session.layer);
			case "prev" -> target = Session.layer == null ? open.isEmpty() ? null : open.last() : open.lower(Session.layer);
			default -> {
				try {
					target = Integer.parseInt(which);
				} catch (NumberFormatException e) {
					Msg.error("Use /gm layer <y>, next, prev or all.");
					return;
				}
			}
		}
		if (target == null && !which.equals("all")) {
			Msg.info(open.isEmpty() ? "Everything is built." : "No more layers that way. /gm layer all shows everything.");
			return;
		}
		Session.layer = target;
		if (target == null) {
			Msg.info("Showing all layers.");
		} else {
			long left = Session.ghosts.stream().filter(g -> g.pos().getY() == target && !built(level.getBlockState(g.pos()), g)).count();
			Msg.info("Showing layer y=" + target + ": " + left + " blocks left there. /gm layer next continues.");
		}
	}

	// --- Placing --------------------------------------------------------------------------------

	/** Sends the unfinished part of the plan to the server for placement. */
	static void apply(boolean replaceContainers) {
		if (Session.ghosts.isEmpty()) {
			Msg.error("No plan to apply.");
			return;
		}
		if (!ClientPlayNetworking.canSend(ApplyChunkPayload.TYPE)) {
			Msg.error("This server does not have Ghost Mason installed, so it cannot place blocks. Build along the ghosts by hand.");
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		List<ApplyChunkPayload.Entry> todo = new ArrayList<>();
		for (Ghost g : Session.ghosts) {
			if (!built(level.getBlockState(g.pos()), g)) {
				todo.add(new ApplyChunkPayload.Entry(g.pos(), g.target()));
			}
		}
		if (todo.isEmpty()) {
			Msg.good("The world already matches the plan.");
			return;
		}
		int batchId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
		for (int from = 0; from < todo.size(); from += ApplyChunkPayload.MAX_ENTRIES) {
			int to = Math.min(todo.size(), from + ApplyChunkPayload.MAX_ENTRIES);
			boolean last = to == todo.size();
			ClientPlayNetworking.send(new ApplyChunkPayload(batchId, last, Session.freePlacement(), replaceContainers,
					List.copyOf(todo.subList(from, to))));
		}
		Msg.info("Sent " + todo.size() + " block changes to the server."
				+ (replaceContainers ? " Containers in the way are broken; their contents drop as items." : ""));
	}

	/** Undo or redo the last placement on the server. */
	static void history(boolean redo) {
		if (!ClientPlayNetworking.canSend(HistoryPayload.TYPE)) {
			Msg.error("This server does not have Ghost Mason installed.");
			return;
		}
		ClientPlayNetworking.send(new HistoryPayload(redo));
	}

	static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("26.3");
	}
}
