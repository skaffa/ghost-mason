package dev.ghostmason.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the system prompt and the region description sent to the AI. */
public final class PromptBuilder {
	/** Symbols used for palette entries. '.' is reserved for air. */
	private static final String SYMBOLS =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#$%&*+=@^~!?<>/|:;";

	private PromptBuilder() {
	}

	/**
	 * @param blockReference the game's own list of valid blocks and their properties
	 *                       (see the client's BlockReference), or "" to leave it out
	 */
	public static String systemPrompt(String minecraftVersion, String blockReference) {
		return systemPrompt(minecraftVersion, blockReference, "");
	}

	/**
	 * @param playerInstructions the player's standing instructions (config/ghostmason/prompt.txt), or ""
	 */
	public static String systemPrompt(String minecraftVersion, String blockReference, String playerInstructions) {
		StringBuilder sb = new StringBuilder(baseSystemPrompt(minecraftVersion));
		if (playerInstructions != null && !playerInstructions.isBlank()) {
			sb.append("\nStanding instructions from the player. Follow them in every design, unless they would ")
					.append("break the reply format or the placement rules above:\n")
					.append(playerInstructions.strip()).append('\n');
		}
		if (blockReference != null && !blockReference.isBlank()) {
			sb.append("\nValid blocks in this game, grouped by their state properties. Use only these ids ")
					.append("(namespace minecraft: unless shown) and only these property values; properties you leave out ")
					.append("take their default value.\n").append(blockReference);
		}
		return sb.toString();
	}

	private static String baseSystemPrompt(String minecraftVersion) {
		return """
				You are Ghost Mason, an expert Minecraft builder and architect. The player selected a \
				box-shaped region of their world and describes what they want built or changed in it. \
				You answer with a build script that Ghost Mason runs to produce the result.

				Coordinates are relative to the region's minimum corner (0,0,0). +x is east, +y is up, \
				+z is south. Every coordinate must be inside the region: 0 <= x < sizeX, 0 <= y < sizeY, \
				0 <= z < sizeZ.

				Reply with ONLY one JSON object, no prose before or after it:
				{
				  "notes": ["3 to 8 short design decisions, written first, max ~90 characters each. \
				For buildings, one note must state the key heights, e.g. floor y=1, windows y=2-3, wall top y=4, eaves y=5, ridge y=9"],
				  "summary": "one or two sentences describing what you built",
				  "ops": [ ... ]
				}
				Write "notes" before anything else: the player watches them appear live.

				Ops run in order; later ops overwrite earlier ones. Available ops:
				  {"op":"fill",    "from":[x,y,z], "to":[x,y,z], "block":"<state>"}   solid box
				  {"op":"hollow",  "from":[x,y,z], "to":[x,y,z], "block":"<state>"}   box shell only, inside untouched
				  {"op":"walls",   "from":[x,y,z], "to":[x,y,z], "block":"<state>"}   four side walls, no floor/roof
				  {"op":"clear",   "from":[x,y,z], "to":[x,y,z]}                      fill with air
				  {"op":"set",     "pos":[x,y,z], "block":"<state>"}                  one block
				  {"op":"line",    "from":[x,y,z], "to":[x,y,z], "block":"<state>"}   straight 3D line
				  {"op":"replace", "from":[x,y,z], "to":[x,y,z], "match":"<block id>", "block":"<state>"}
				  {"op":"cylinder","base":[x,y,z], "radius":r, "height":h, "block":"<state>", "hollow":false}  vertical, base = bottom centre
				  {"op":"sphere",  "center":[x,y,z], "radius":r, "block":"<state>", "hollow":false}

				<state> uses Minecraft block state syntax for Java Edition %s, for example \
				minecraft:oak_planks or minecraft:oak_stairs[facing=east,half=bottom]. Use only real block \
				ids and properties. Doors, beds and tall plants need both halves.

				Orientation and placement rules (these are where builds most often go wrong):
				- Stairs: "facing" is the side with the tall back, the direction you walk UP. A stair \
				used as a chair has its back away from the table, so facing points from the table \
				towards the chair (a chair east of its table is facing=east). Staircases: facing points \
				in the direction of climbing.
				- Roof stairs: facing points uphill, towards the ridge, so the two slopes of a gable use \
				opposite facings. Upside-down stairs (half=top) make eaves and trims under overhangs.
				- Slabs: type=bottom fills the lower half of its block, type=top the upper half. Use \
				type=top for roof brims, eaves, overhang trims, ceilings and table tops; type=bottom for \
				paths, floors, low steps and flat roof caps; type=double for a full block.
				- Logs and pillars: axis=y stands upright; axis=x or axis=z lies along that axis as a beam.
				- Beds: part=foot and part=head, with facing pointing from the foot to the head.
				- Wall-mounted blocks (wall_torch, ladder, wall signs, buttons, wall banners): facing \
				points away from the wall they hang on, and the block behind them must have a full solid \
				face. Never mount them on glass panes, fences, iron bars, walls or the side of a slab or \
				stair. Standing torches and lanterns need a solid block below; hanging lanterns \
				(hanging=true) hang under a block.
				Blocks that cannot stay where you put them are removed automatically, so place lights \
				and attachments on solid blocks.

				Walls, windows and roofs (work these out as numbers before writing ops):
				- Decide the wall top height first. Windows end at least one full block BELOW the wall \
				top: a solid lintel course (wall material, a beam log, or an upside-down stair) always \
				separates the highest window row from the roof. Never put glass in the top wall row.
				- The roof starts ABOVE the wall top: its lowest stairs or slabs sit at wall top + 1, or \
				at the wall top only when they are outside the wall line. Roof blocks never occupy a \
				window's position or the block directly outside a window.
				- Eaves overhang the walls by one block. The overhanging edge pieces are stairs facing \
				uphill, or slabs with type=top so they hang from the roof instead of sitting on the eave \
				line like a step. Roof brims and edge trims are type=top slabs, never type=bottom.
				- A gable roof rises one block per block inwards: on each slope, the stair one block \
				further in is one block higher. Fill the triangular gable ends with wall material \
				(no glass there unless asked) and keep the space under the roof closed.
				- Doors are two blocks high in a wall opening; keep the block above the door solid.

				Work with the existing terrain shown in the region description: build on the ground, \
				clear what is in the way, and keep anything the player did not ask to change. Prefer a \
				few large ops over many single-block sets. Make builds look good: use depth, varied \
				materials, trims, and proper roofs.

				When the player asks for a revision, reply with the complete updated script, applied to \
				the ORIGINAL region shown in the first message, not on top of your previous script.
				""".formatted(minecraftVersion);
	}

	/** The first user message: the region contents plus the request. */
	public static String initialRequest(RegionSnapshot region, String request) {
		return describe(region) + "\nRequest: " + request.trim();
	}

	/**
	 * Asks the AI to look over its own result. Deliberately open-ended: it steers the model to
	 * judge the build like a builder would, instead of checking a fixed list of rules.
	 *
	 * @param result the region exactly as the last script produced it
	 * @param facts  problems Ghost Mason itself noticed, or empty
	 */
	public static String reviewRequest(RegionSnapshot result, java.util.List<String> facts) {
		StringBuilder sb = new StringBuilder();
		sb.append("Review the build you just made, the way an experienced Minecraft builder would walk around it ")
				.append("and look at it block by block. This is the region exactly as your script produced it:\n\n")
				.append(describe(result));
		if (!facts.isEmpty()) {
			sb.append("\nGhost Mason also noticed:\n");
			for (String f : facts) {
				sb.append("- ").append(f).append('\n');
			}
		}
		sb.append("\nIf anything would look wrong in the game, fix it: things that float, face the wrong way, ")
				.append("block a window or door, leave gaps, clash with each other, or do not match the request. ")
				.append("Then reply with the complete corrected JSON script for the original region, with notes saying what you fixed. ")
				.append("If the build is already good, reply with exactly {\"ok\": true} and nothing else.");
		return sb.toString();
	}

	/** Longest stretch of earlier reasoning passed on when the player cuts the thinking short. */
	private static final int RUSH_REASONING_CHARS = 12000;

	/**
	 * Added to the request when the player stopped the AI while it was still thinking and wants
	 * the build right away.
	 *
	 * @param reasoningSoFar the visible reasoning before the stop, or "" when the provider hides it
	 */
	public static String rushInstruction(String reasoningSoFar) {
		StringBuilder sb = new StringBuilder("\n\nThe player stopped your deliberation and wants the build now.");
		if (reasoningSoFar != null && !reasoningSoFar.isBlank()) {
			String r = reasoningSoFar.strip();
			if (r.length() > RUSH_REASONING_CHARS) {
				r = "..." + r.substring(r.length() - RUSH_REASONING_CHARS);
			}
			sb.append(" This is what you had worked out so far:\n<your_plan_so_far>\n").append(r).append("\n</your_plan_so_far>\n");
		}
		sb.append(" Do not think it over again: write the complete JSON build script immediately, ")
				.append("based on the plan so far. A simpler build is fine.");
		return sb.toString();
	}

	public static String revisionRequest(String request) {
		return "Revise the build: " + request.trim()
				+ "\nReply with the complete updated JSON script for the original region.";
	}

	/**
	 * Region contents as a palette plus one text grid per y layer.
	 * Each grid row is one z value; each character is one x value.
	 */
	public static String describe(RegionSnapshot region) {
		Map<String, Character> palette = new LinkedHashMap<>();
		StringBuilder layers = new StringBuilder();
		boolean overflow = false;
		String ruler = xRuler(region.sizeX, region.sizeZ);

		for (int y = 0; y < region.sizeY; y++) {
			StringBuilder layer = new StringBuilder(ruler);
			boolean anySolid = false;
			for (int z = 0; z < region.sizeZ; z++) {
				layer.append(rowLabel(z, region.sizeZ));
				for (int x = 0; x < region.sizeX; x++) {
					String state = region.get(x, y, z);
					char c;
					if (RegionSnapshot.isAir(state)) {
						c = '.';
					} else {
						anySolid = true;
						Character sym = palette.get(state);
						if (sym == null) {
							if (palette.size() < SYMBOLS.length()) {
								sym = SYMBOLS.charAt(palette.size());
								palette.put(state, sym);
							} else {
								overflow = true;
								sym = '_';
							}
						}
						c = sym;
					}
					layer.append(c);
				}
				layer.append('\n');
			}
			layers.append("y=").append(y);
			if (anySolid) {
				layers.append('\n').append(layer);
			} else {
				layers.append(" (all air)\n");
			}
		}

		StringBuilder out = new StringBuilder();
		out.append("Region size: sizeX=").append(region.sizeX)
				.append(" sizeY=").append(region.sizeY)
				.append(" sizeZ=").append(region.sizeZ).append('\n');
		out.append("Palette ('.' is air):\n");
		for (Map.Entry<String, Character> e : palette.entrySet()) {
			out.append(e.getValue()).append(" = ").append(e.getKey()).append('\n');
		}
		if (overflow) {
			out.append("_ = other blocks (too many kinds to list)\n");
		}
		out.append("\nLayers from bottom (y=0) to top. Each layer starts with x column numbers (read the digits ")
				.append("top to bottom, e.g. 1 over 2 is x=12); each row starts with its z number. ")
				.append("Read positions from these labels instead of counting characters.\n");
		out.append(layers);
		return out.toString();
	}

	/** "z= 7 " style row label, padded so every row of the grid lines up. */
	private static String rowLabel(int z, int sizeZ) {
		int width = String.valueOf(Math.max(0, sizeZ - 1)).length();
		return "z=" + " ".repeat(width - String.valueOf(z).length()) + z + " ";
	}

	/** Column numbers above the grid, one digit per column, tens (and hundreds) on the lines above. */
	private static String xRuler(int sizeX, int sizeZ) {
		int pad = rowLabel(0, sizeZ).length();
		int digits = String.valueOf(Math.max(0, sizeX - 1)).length();
		StringBuilder sb = new StringBuilder();
		for (int d = digits - 1; d >= 0; d--) {
			sb.append(d == 0 ? "x" : " ").append(" ".repeat(pad - 1));
			int unit = (int) Math.pow(10, d);
			for (int x = 0; x < sizeX; x++) {
				// Leading zeros of higher digits are shown as spaces for readability.
				sb.append(d > 0 && x < unit ? ' ' : Character.forDigit((x / unit) % 10, 10));
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}
