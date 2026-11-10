package dev.ghostmason.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * The /gm client command. Everything lives under /gm so nothing clashes with other mods,
 * and settings share one /gm config subcommand instead of separate commands.
 */
final class GmCommands {
	private static final List<String> PROVIDERS = List.of("claudecode", "claude", "deepseek");
	private static final List<String> VERBOSITY = List.of("off", "brief", "full");

	private GmCommands() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("gm")
				.executes(c -> help())
				.then(literal("pos1").executes(c -> setPos(1)))
				.then(literal("pos2").executes(c -> setPos(2)))
				.then(literal("build").then(argument("description", StringArgumentType.greedyString())
						.executes(c -> run(() -> Planner.build(StringArgumentType.getString(c, "description"))))))
				.then(literal("refine")
						.then(literal("back").executes(c -> run(Planner::back)))
						.then(argument("change", StringArgumentType.greedyString())
								.executes(c -> run(() -> Planner.refine(StringArgumentType.getString(c, "change"))))))
				.then(literal("move").then(argument("dx", IntegerArgumentType.integer(-256, 256))
						.then(argument("dy", IntegerArgumentType.integer(-256, 256))
								.then(argument("dz", IntegerArgumentType.integer(-256, 256))
										.executes(c -> run(() -> Planner.move(IntegerArgumentType.getInteger(c, "dx"),
												IntegerArgumentType.getInteger(c, "dy"), IntegerArgumentType.getInteger(c, "dz"))))))))
				.then(literal("rotate")
						.executes(c -> run(() -> Planner.rotate(90)))
						.then(literal("90").executes(c -> run(() -> Planner.rotate(90))))
						.then(literal("180").executes(c -> run(() -> Planner.rotate(180))))
						.then(literal("270").executes(c -> run(() -> Planner.rotate(270)))))
				.then(literal("layer")
						.executes(c -> run(() -> Planner.layer("next")))
						.then(argument("which", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("next", "prev", "all"), b))
								.executes(c -> run(() -> Planner.layer(StringArgumentType.getString(c, "which"))))))
				.then(literal("cancel")
						.executes(c -> run(() -> Planner.cancel(false)))
						.then(literal("keep").executes(c -> run(() -> Planner.cancel(true)))))
				.then(literal("apply")
						.executes(c -> run(() -> Planner.apply(false)))
						.then(literal("replace").executes(c -> run(() -> Planner.apply(true)))))
				.then(literal("undo").executes(c -> run(() -> Planner.history(false))))
				.then(literal("redo").executes(c -> run(() -> Planner.history(true))))
				.then(literal("clear")
						.executes(c -> clear("plan"))
						.then(argument("what", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("plan", "selection", "all"), b))
								.executes(c -> clear(StringArgumentType.getString(c, "what")))))
				.then(literal("diff")
						.executes(c -> diff(!Session.diffOn))
						.then(literal("on").executes(c -> diff(true)))
						.then(literal("off").executes(c -> diff(false))))
				.then(literal("export")
						.executes(c -> export(""))
						.then(argument("name", StringArgumentType.word()).executes(c -> export(StringArgumentType.getString(c, "name")))))
				.then(literal("status").executes(c -> status()))
				.then(literal("config")
						.executes(c -> showConfig())
						.then(literal("list").executes(c -> listConfig()))
						.then(literal("provider").then(argument("name", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(PROVIDERS, b))
								.executes(GmCommands::setProvider)))
						.then(literal("model").then(argument("id", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(ModelSuggestions.forCurrentProvider(), b))
								.executes(GmCommands::setModel)))
						.then(literal("maxghosts").then(argument("count", IntegerArgumentType.integer(100, 1_000_000))
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("4000", "10000", "25000", "50000"), b))
								.executes(GmCommands::setMaxGhosts)))
						.then(literal("maxvolume").then(argument("blocks", IntegerArgumentType.integer(1000, 10_000_000))
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("32768", "65536", "131072", "262144"), b))
								.executes(GmCommands::setMaxVolume)))
						.then(literal("ghostdistance").then(argument("blocks", IntegerArgumentType.integer(8, 512))
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("48", "96", "160", "256"), b))
								.executes(GmCommands::setGhostDistance)))
						.then(literal("effort").then(argument("level", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(effortLevels(), b))
								.executes(GmCommands::setEffort)))
						.then(literal("maxtokens").then(argument("tokens", IntegerArgumentType.integer(0, 1_000_000))
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("16000", "32000", "64000", "128000"), b))
								.executes(GmCommands::setMaxTokens)))
						.then(literal("verbosity").then(argument("level", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(VERBOSITY, b))
								.executes(GmCommands::setVerbosity)))
						.then(literal("placement").then(argument("mode", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("checked", "free"), b))
								.executes(GmCommands::setPlacement)))
						.then(literal("tokens").then(argument("state", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("on", "off"), b))
								.executes(GmCommands::setTokens)))
						.then(literal("review").then(argument("state", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("on", "off"), b))
								.executes(GmCommands::setReview)))
						.then(literal("worldedit").then(argument("state", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("on", "off"), b))
								.executes(GmCommands::setWorldEdit)))
						.then(literal("key").then(argument("provider", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("claude", "deepseek"), b))
								.then(argument("key", StringArgumentType.greedyString())
										.executes(GmCommands::setKey))))
						.then(literal("connect").executes(c -> run(() -> KeyLink.begin(Session.config.provider, null))))
						.then(literal("reload").executes(c -> {
							Session.reloadConfig();
							Msg.good("Config reloaded.");
							return 1;
						}))));
	}

	private static int run(Runnable action) {
		action.run();
		return 1;
	}

	private static int help() {
		Msg.info("/gm pos1, /gm pos2 - mark the corners (block you look at, or where you stand)");
		Msg.info("/gm build <description> | /gm refine <change|back> - design, revise, or go back a version");
		Msg.info("/gm cancel [keep] - stop the AI; keep uses what it has so far, or asks it to build right away");
		Msg.info("/gm move <dx> <dy> <dz> | /gm rotate [90|180|270] - reposition the design, no AI needed");
		Msg.info("/gm layer [<y>|next|prev|all] - show one layer at a time");
		Msg.info("/gm apply [replace] | /gm undo | /gm redo - place it (creative or op); replace also breaks containers in the way");
		Msg.info("/gm diff [on|off] - highlight what the last design changed");
		Msg.info("/gm export [name] - save the design as a .schem file");
		Msg.info("/gm status - progress and the blocks you still need | /gm clear [plan|selection|all]");
		Msg.info("/gm config [list|provider|model|effort|maxtokens|verbosity|tokens|placement|review|worldedit|maxvolume|maxghosts|ghostdistance|key|connect|reload] - settings");
		Msg.info("Standing instructions for the AI: edit config/ghostmason/prompt.txt");
		return 1;
	}

	private static int setPos(int which) {
		Minecraft client = Minecraft.getInstance();
		BlockPos pos;
		HitResult hit = client.hitResult;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			pos = blockHit.getBlockPos();
		} else {
			pos = client.player.blockPosition();
		}
		if (which == 1) {
			Session.pos1 = pos;
		} else {
			Session.pos2 = pos;
		}
		WorldEditSelection.source = "Ghost Mason";
		String msg = "Corner " + which + " set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".";
		if (Session.hasSelection()) {
			BlockPos min = Session.selectionMin();
			BlockPos max = Session.selectionMax();
			msg += " Selection " + (max.getX() - min.getX() + 1) + "x" + (max.getY() - min.getY() + 1) + "x"
					+ (max.getZ() - min.getZ() + 1) + " (" + Session.selectionVolume() + " blocks).";
		}
		Msg.info(msg);
		return 1;
	}

	private static int clear(String what) {
		switch (what.toLowerCase(Locale.ROOT)) {
			case "plan" -> {
				Session.clearPlan();
				Msg.info("Plan discarded.");
			}
			case "selection" -> {
				Session.pos1 = null;
				Session.pos2 = null;
				Msg.info("Selection cleared.");
			}
			case "all" -> {
				Session.clearPlan();
				Session.pos1 = null;
				Session.pos2 = null;
				Msg.info("Plan and selection cleared.");
			}
			default -> {
				Msg.error("Use /gm clear plan, selection or all.");
				return 0;
			}
		}
		return 1;
	}

	private static int diff(boolean on) {
		Session.diffOn = on;
		if (!on) {
			Msg.info("Diff overlay off.");
			return 1;
		}
		if (Session.diff.isEmpty()) {
			Msg.info("Diff overlay on. There is nothing to compare yet.");
			return 1;
		}
		long added = Session.diff.stream().filter(d -> d.kind() == Session.DiffKind.ADDED).count();
		long removed = Session.diff.stream().filter(d -> d.kind() == Session.DiffKind.REMOVED).count();
		long changed = Session.diff.size() - added - removed;
		String against = Session.versions.size() >= 2 ? "the previous design" : "the original terrain";
		Msg.send(Component.literal("Diff vs " + against + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(added + " added").withStyle(ChatFormatting.GREEN))
				.append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(removed + " removed").withStyle(ChatFormatting.RED))
				.append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(changed + " changed").withStyle(ChatFormatting.GOLD)));
		return 1;
	}

	private static int export(String name) {
		if (Session.latest() == null) {
			Msg.error("No design to export. Make one with /gm build <description>.");
			return 0;
		}
		try {
			Path file = SchematicExporter.export(name.isBlank() ? SchematicExporter.defaultName() : name);
			Msg.send(Component.literal("Saved ").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(file.getFileName().toString())
							.withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
									.withClickEvent(new ClickEvent.OpenFile(file.getParent()))))
					.append(Component.literal(" in the schematics folder (click to open). WorldEdit and Litematica can load it.")
							.withStyle(ChatFormatting.GRAY)));
			return 1;
		} catch (IOException | RuntimeException e) {
			Msg.error("Export failed: " + e.getMessage());
			return 0;
		}
	}

	private static int status() {
		Msg.info("AI: " + Session.config.provider + ", model " + currentModel() + ", verbosity " + Session.config.verbosity + ".");
		Msg.info(Session.hasSelection()
				? "Selection: " + Session.selectionVolume() + " blocks (from " + WorldEditSelection.source + ")."
				: "No selection.");
		if (Session.busy) {
			Msg.info("Waiting for the AI...");
		}
		if (!Session.ghosts.isEmpty()) {
			Msg.info("Plan v" + Session.versions.size() + ": " + Session.remaining + " of " + Session.ghosts.size()
					+ " blocks left to build. Diff overlay " + (Session.diffOn ? "on" : "off")
					+ (Session.layer != null ? ", showing layer y=" + Session.layer : "") + ".");
			Materials.report();
			if (Session.remaining > 0 && Session.remaining <= 10) {
				listLeftovers();
			}
		}
		return 1;
	}

	/** The last few unbuilt blocks, planned versus actual, so it is clear what is still missing. */
	private static void listLeftovers() {
		var level = Minecraft.getInstance().level;
		Msg.info("Still different from the plan:");
		for (Ghost g : Session.ghosts) {
			var live = level.getBlockState(g.pos());
			if (Planner.built(live, g)) {
				continue;
			}
			BlockPos p = g.pos();
			Msg.send(Component.literal("  " + p.getX() + " " + p.getY() + " " + p.getZ() + ": wants ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(shortState(g.target())).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(", has ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(shortState(live)).withStyle(ChatFormatting.YELLOW)));
		}
	}

	private static String shortState(net.minecraft.world.level.block.state.BlockState state) {
		return net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state).replace("minecraft:", "");
	}

	private static int showConfig() {
		Msg.info("provider = " + Session.config.provider + "   (claudecode, claude, deepseek)");
		Msg.info("model = " + currentModel());
		Msg.info("effort = " + effortText() + "   (" + String.join(", ", effortLevels()) + ")");
		Msg.info("maxtokens = " + maxTokensText() + "   (output limit per answer, for this provider)");
		Msg.info("verbosity = " + Session.config.verbosity + "   (off, brief, full)");
		Msg.info("placement = " + Session.config.placement + "   (checked, free)");
		Msg.info("review = " + (Session.config.review ? "on" : "off") + "   (on, off)");
		Msg.info("tokens = " + (Session.config.showTokens ? "on" : "off") + "   (on, off)");
		Msg.info("worldedit = " + (Session.config.worldedit ? "on" : "off") + "   (on, off)"
				+ (WorldEditSelection.available() ? "" : " - WorldEdit/WorldEditCUI not installed"));
		Msg.info("Change with /gm config <setting> <value>. /gm config list shows every setting; /gm config connect links an AI.");
		return 1;
	}

	private static String currentModel() {
		return switch (Session.config.provider.toLowerCase(Locale.ROOT)) {
			case "deepseek" -> Session.config.deepseekModel;
			case "claudecode" -> Session.config.claudeCodeModel.isBlank() ? "Claude Code's default" : Session.config.claudeCodeModel;
			default -> Session.config.anthropicModel;
		};
	}

	private static int setProvider(CommandContext<FabricClientCommandSource> c) {
		String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
		if (!PROVIDERS.contains(name)) {
			Msg.error("Unknown provider. Use claudecode, claude or deepseek.");
			return 0;
		}
		Session.config.provider = name;
		Session.saveConfig();
		Msg.good("Provider set to " + name + " (model " + currentModel() + ").");
		return 1;
	}

	private static int setModel(CommandContext<FabricClientCommandSource> c) {
		String id = StringArgumentType.getString(c, "id").trim();
		switch (Session.config.provider.toLowerCase(Locale.ROOT)) {
			case "deepseek" -> Session.config.deepseekModel = id;
			case "claudecode" -> Session.config.claudeCodeModel = id.equals("default") ? "" : id;
			default -> Session.config.anthropicModel = id;
		}
		Session.saveConfig();
		Msg.good("Model set to " + currentModel() + ".");
		return 1;
	}

	private static int setMaxGhosts(CommandContext<FabricClientCommandSource> c) {
		int count = IntegerArgumentType.getInteger(c, "count");
		Session.config.maxGhostsDrawn = count;
		Session.saveConfig();
		Msg.good("Up to " + String.format(Locale.ROOT, "%,d", count) + " ghosts are drawn at once (nearest first within "
				+ Session.config.ghostRenderDistance + " blocks).");
		if (count > 20000) {
			Msg.warn("Large numbers of ghosts can lower your frame rate, especially on integrated graphics.");
		}
		return 1;
	}

	private static int setMaxVolume(CommandContext<FabricClientCommandSource> c) {
		int blocks = IntegerArgumentType.getInteger(c, "blocks");
		Session.config.maxRegionVolume = blocks;
		if (Session.config.maxChanges < blocks) {
			Session.config.maxChanges = blocks; // a design may then change every block of the selection
		}
		Session.saveConfig();
		Msg.good("Selections up to " + String.format(Locale.ROOT, "%,d", blocks) + " blocks can be sent to the AI.");
		if (blocks > 65536) {
			Msg.warn("Big selections make big requests: roughly 1 token per 3 blocks of terrain, more time and cost per design. "
					+ "A server may also cap each /gm apply (run it again for the rest).");
		}
		return 1;
	}

	private static int setGhostDistance(CommandContext<FabricClientCommandSource> c) {
		int blocks = IntegerArgumentType.getInteger(c, "blocks");
		Session.config.ghostRenderDistance = blocks;
		Session.saveConfig();
		Msg.good("Ghosts are drawn up to " + blocks + " blocks away (at most "
				+ String.format(Locale.ROOT, "%,d", Session.config.maxGhostsDrawn) + " at once).");
		return 1;
	}

	/** Every setting in config/ghostmason/client.json, with API keys partly hidden. */
	private static int listConfig() {
		com.google.gson.JsonObject all = new com.google.gson.Gson().toJsonTree(Session.config).getAsJsonObject();
		Msg.info("All settings (config/ghostmason/client.json):");
		for (var entry : all.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString();
			if (key.toLowerCase(Locale.ROOT).endsWith("apikey")) {
				value = redact(value, key.startsWith("anthropic") ? "ANTHROPIC_API_KEY" : "DEEPSEEK_API_KEY");
			} else if (value.isEmpty()) {
				value = "(empty)";
			}
			Msg.send(Component.literal("  " + key + " = ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
		}
		return 1;
	}

	/** Shows only the start and end of a key, so it can be recognised but not copied. */
	private static String redact(String key, String envVar) {
		if (key == null || key.isBlank()) {
			String env = System.getenv(envVar);
			return env == null || env.isBlank() ? "(not set)" : "(not set; using " + envVar + ": " + redact(env, envVar) + ")";
		}
		String k = key.trim();
		if (k.length() <= 12) {
			return "*".repeat(k.length());
		}
		return k.substring(0, 6) + "..." + k.substring(k.length() - 4) + " (" + k.length() + " characters)";
	}

	/** Effort levels the current provider understands; "default" leaves the provider's own choice. */
	private static List<String> effortLevels() {
		return Session.config.provider.equalsIgnoreCase("deepseek")
				? List.of("off", "low", "high", "max")
				: List.of("default", "low", "medium", "high", "xhigh", "max");
	}

	private static String effortText() {
		String value = switch (Session.config.provider.toLowerCase(Locale.ROOT)) {
			case "deepseek" -> Session.config.deepseekEffort;
			case "claudecode" -> Session.config.claudeCodeEffort;
			default -> Session.config.anthropicEffort;
		};
		return value == null || value.isBlank() ? "default" : value;
	}

	private static int setEffort(CommandContext<FabricClientCommandSource> c) {
		String level = StringArgumentType.getString(c, "level").toLowerCase(Locale.ROOT);
		if (!effortLevels().contains(level)) {
			Msg.error("Use one of: " + String.join(", ", effortLevels()) + ".");
			return 0;
		}
		String stored = level.equals("default") ? "" : level;
		switch (Session.config.provider.toLowerCase(Locale.ROOT)) {
			case "deepseek" -> Session.config.deepseekEffort = stored;
			case "claudecode" -> Session.config.claudeCodeEffort = stored;
			default -> Session.config.anthropicEffort = stored;
		}
		Session.saveConfig();
		Msg.good("Effort for " + Session.config.provider + " set to " + effortText()
				+ ". Lower is faster and cheaper; higher thinks longer about complex builds.");
		return 1;
	}

	private static String maxTokensText() {
		return switch (Session.config.provider.toLowerCase(Locale.ROOT)) {
			case "deepseek" -> String.valueOf(Session.config.deepseekMaxTokens);
			case "claudecode" -> Session.config.claudeCodeMaxTokens <= 0 ? "Claude Code's default" : String.valueOf(Session.config.claudeCodeMaxTokens);
			default -> String.valueOf(Session.config.anthropicMaxTokens);
		};
	}

	private static int setMaxTokens(CommandContext<FabricClientCommandSource> c) {
		int tokens = IntegerArgumentType.getInteger(c, "tokens");
		String provider = Session.config.provider.toLowerCase(Locale.ROOT);
		if (tokens < 1024 && !(provider.equals("claudecode") && tokens == 0)) {
			Msg.error("Use at least 1024 tokens; a build script needs room." + (provider.equals("claudecode") ? " 0 means Claude Code's default." : ""));
			return 0;
		}
		switch (provider) {
			case "deepseek" -> Session.config.deepseekMaxTokens = tokens;
			case "claudecode" -> Session.config.claudeCodeMaxTokens = tokens;
			default -> Session.config.anthropicMaxTokens = tokens;
		}
		Session.saveConfig();
		Msg.good("Max tokens for " + provider + " set to " + maxTokensText() + ". Providers bill only what is used, so this is a ceiling, not a cost.");
		return 1;
	}

	private static int setVerbosity(CommandContext<FabricClientCommandSource> c) {
		String level = StringArgumentType.getString(c, "level").toLowerCase(Locale.ROOT);
		if (!VERBOSITY.contains(level)) {
			Msg.error("Use off, brief or full.");
			return 0;
		}
		Session.config.verbosity = level;
		Session.saveConfig();
		Msg.good(switch (level) {
			case "off" -> "Verbosity off: only the finished design is shown.";
			case "full" -> "Verbosity full: progress, design notes, and the AI's reasoning where the provider shares it.";
			default -> "Verbosity brief: progress and the AI's design notes.";
		});
		if (level.equals("full") && Session.config.provider.equalsIgnoreCase("claudecode")) {
			Msg.info("Claude Code does not pass on Claude's reasoning, so full looks like brief there.");
		}
		return 1;
	}

	private static int setPlacement(CommandContext<FabricClientCommandSource> c) {
		String mode = StringArgumentType.getString(c, "mode").toLowerCase(Locale.ROOT);
		if (!mode.equals("checked") && !mode.equals("free")) {
			Msg.error("Use checked or free.");
			return 0;
		}
		Session.config.placement = mode;
		Session.saveConfig();
		if (mode.equals("free")) {
			Msg.warn("Placement free: every block goes exactly where the AI put it, even without support. "
					+ "Unsupported blocks can still drop later when something next to them changes.");
		} else {
			Msg.good("Placement checked: blocks that could not stay are re-aimed or left out.");
		}
		Msg.info("Applies to the next design. /gm refine with a small change re-checks the current one.");
		return 1;
	}

	private static int setTokens(CommandContext<FabricClientCommandSource> c) {
		String state = StringArgumentType.getString(c, "state").toLowerCase(Locale.ROOT);
		if (!state.equals("on") && !state.equals("off")) {
			Msg.error("Use on or off.");
			return 0;
		}
		Session.config.showTokens = state.equals("on");
		Session.saveConfig();
		Msg.good(Session.config.showTokens
				? "Token count on: live in the action bar while designing, final count in chat."
				: "Token count off.");
		return 1;
	}

	private static int setReview(CommandContext<FabricClientCommandSource> c) {
		String state = StringArgumentType.getString(c, "state").toLowerCase(Locale.ROOT);
		if (!state.equals("on") && !state.equals("off")) {
			Msg.error("Use on or off.");
			return 0;
		}
		Session.config.review = state.equals("on");
		Session.saveConfig();
		Msg.good(Session.config.review
				? "Review on: after each design the AI looks over its result once and fixes what it finds. Takes about twice as long."
				: "Review off.");
		return 1;
	}

	private static int setWorldEdit(CommandContext<FabricClientCommandSource> c) {
		String state = StringArgumentType.getString(c, "state").toLowerCase(Locale.ROOT);
		if (!state.equals("on") && !state.equals("off")) {
			Msg.error("Use on or off.");
			return 0;
		}
		Session.config.worldedit = state.equals("on");
		Session.saveConfig();
		if (!Session.config.worldedit) {
			Msg.good("WorldEdit selection off. Use /gm pos1 and /gm pos2.");
		} else if (!WorldEditSelection.available()) {
			Msg.warn("Saved, but WorldEdit (or WorldEditCUI) is not installed on this client, so /gm pos1 and /gm pos2 stay in use.");
		} else {
			WorldEditSelection.requestUpdates();
			Msg.good("WorldEdit selection on: your //pos1 //pos2 or wand selection is used. /gm pos1 and /gm pos2 still work too.");
		}
		return 1;
	}

	private static int setKey(CommandContext<FabricClientCommandSource> c) {
		String provider = StringArgumentType.getString(c, "provider").toLowerCase(Locale.ROOT);
		String key = StringArgumentType.getString(c, "key").trim();
		switch (provider) {
			case "claude", "anthropic" -> Session.config.anthropicApiKey = key;
			case "deepseek" -> Session.config.deepseekApiKey = key;
			default -> {
				Msg.error("Keys are for claude or deepseek. Claude Code uses its own sign-in: /gm config connect.");
				return 0;
			}
		}
		Session.saveConfig();
		Msg.good("Key saved. It also stays in your chat history until you restart; /gm config connect avoids that.");
		return 1;
	}
}
