package dev.ghostmason.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the AI's JSON build script against a region snapshot and returns the resulting
 * changes. The script is data, never code: only the ops listed here exist.
 */
public final class BuildScript {
	private static final String AIR = "minecraft:air";

	/**
	 * @param summary  the AI's description of the build
	 * @param changes  region index to new block state, only where it differs from the snapshot
	 * @param warnings problems that were skipped over
	 * @param finalStates every position of the region after the script ran, indexed like the snapshot
	 */
	public record Result(String summary, Map<Integer, String> changes, List<String> warnings, String[] finalStates) {
	}

	private final RegionSnapshot region;
	private final String[] work;
	private final List<String> warnings = new ArrayList<>();
	private boolean clipped;

	private BuildScript(RegionSnapshot region) {
		this.region = region;
		this.work = region.copyStates();
	}

	public static Result run(String aiText, RegionSnapshot region, int maxChanges) throws ScriptException {
		JsonObject root = extractJson(aiText);
		BuildScript script = new BuildScript(region);

		String summary = root.has("summary") && root.get("summary").isJsonPrimitive()
				? root.get("summary").getAsString() : "";

		if (!root.has("ops") || !root.get("ops").isJsonArray()) {
			throw new ScriptException("The AI's answer has no \"ops\" list.");
		}
		JsonArray ops = root.getAsJsonArray("ops");
		int n = 0;
		for (JsonElement el : ops) {
			n++;
			if (!el.isJsonObject()) {
				script.warnings.add("Op " + n + " is not an object; skipped.");
				continue;
			}
			try {
				script.apply(el.getAsJsonObject());
			} catch (ScriptException | RuntimeException e) {
				script.warnings.add("Op " + n + " skipped: " + e.getMessage());
			}
		}
		if (script.clipped) {
			script.warnings.add("Some blocks fell outside the selection and were dropped.");
		}

		Map<Integer, String> changes = new LinkedHashMap<>();
		for (int i = 0; i < script.work.length; i++) {
			String before = region.get(i);
			String after = script.work[i];
			boolean same = after.equals(before) || (RegionSnapshot.isAir(after) && RegionSnapshot.isAir(before));
			if (!same) {
				changes.put(i, after);
				if (changes.size() > maxChanges) {
					throw new ScriptException("The plan changes more than " + maxChanges + " blocks (maxChanges in config).");
				}
			}
		}
		return new Result(summary, changes, script.warnings, script.work);
	}

	/**
	 * Rebuilds a valid script from an answer that was stopped half-way: keeps every op the AI
	 * had fully written, drops the unfinished one.
	 *
	 * @return the reconstructed JSON script, or null when no op was complete yet
	 */
	public static String salvage(String partial) {
		int key = partial.indexOf("\"ops\"");
		int start = key < 0 ? -1 : partial.indexOf('[', key);
		if (start < 0) {
			return null;
		}
		JsonArray ops = new JsonArray();
		int i = start + 1;
		while (i < partial.length()) {
			char c = partial.charAt(i);
			if (c == ']') {
				break;
			}
			if (c != '{') {
				i++;
				continue;
			}
			int end = matchingBrace(partial, i);
			if (end < 0) {
				break; // the op being written when the run stopped
			}
			try {
				JsonElement op = JsonParser.parseString(partial.substring(i, end + 1));
				if (op.isJsonObject()) {
					ops.add(op);
				}
			} catch (RuntimeException ignored) {
				// skip a malformed op
			}
			i = end + 1;
		}
		if (ops.isEmpty()) {
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(partial);
		String summary = "";
		if (m.find()) {
			try {
				summary = JsonParser.parseString("\"" + m.group(1) + "\"").getAsString();
			} catch (RuntimeException ignored) {
				// keep it empty
			}
		}
		JsonObject root = new JsonObject();
		root.addProperty("summary", "Stopped early, " + ops.size() + " build steps kept. " + summary);
		root.add("ops", ops);
		return root.toString();
	}

	/** Index of the brace closing the object that starts at {@code open}, or -1 if it is not closed yet. */
	private static int matchingBrace(String s, int open) {
		int depth = 0;
		boolean inString = false;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					inString = false;
				}
			} else if (c == '"') {
				inString = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/** True when the reply is a review saying the build needs no changes: {"ok": true} without ops. */
	public static boolean isApproval(String aiText) {
		try {
			JsonObject root = extractJson(aiText);
			return !root.has("ops") && root.has("ok") && root.get("ok").isJsonPrimitive() && root.get("ok").getAsBoolean();
		} catch (ScriptException | RuntimeException e) {
			return false;
		}
	}

	/** Finds the JSON object in the reply, tolerating markdown fences or stray prose. */
	static JsonObject extractJson(String text) throws ScriptException {
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new ScriptException("The AI did not answer with a JSON build script.");
		}
		try {
			JsonElement el = JsonParser.parseString(text.substring(start, end + 1));
			if (el.isJsonObject()) {
				return el.getAsJsonObject();
			}
		} catch (RuntimeException e) {
			throw new ScriptException("The AI's build script is not valid JSON: " + e.getMessage());
		}
		throw new ScriptException("The AI's build script is not a JSON object.");
	}

	private void apply(JsonObject op) throws ScriptException {
		String type = str(op, "op").toLowerCase(Locale.ROOT);
		switch (type) {
			case "fill" -> box(vec(op, "from"), vec(op, "to"), block(op), Shape.SOLID);
			case "hollow" -> box(vec(op, "from"), vec(op, "to"), block(op), Shape.SHELL);
			case "walls" -> box(vec(op, "from"), vec(op, "to"), block(op), Shape.WALLS);
			case "clear" -> box(vec(op, "from"), vec(op, "to"), AIR, Shape.SOLID);
			case "set" -> {
				int[] p = vec(op, "pos");
				put(p[0], p[1], p[2], block(op));
			}
			case "line" -> line(vec(op, "from"), vec(op, "to"), block(op));
			case "replace" -> replace(vec(op, "from"), vec(op, "to"), normalize(str(op, "match")), block(op));
			case "cylinder" -> cylinder(vec(op, "base"), num(op, "radius"), (int) num(op, "height"), block(op), bool(op, "hollow"));
			case "sphere" -> sphere(vec(op, "center"), num(op, "radius"), block(op), bool(op, "hollow"));
			default -> throw new ScriptException("unknown op '" + type + "'");
		}
	}

	private enum Shape { SOLID, SHELL, WALLS }

	private void box(int[] a, int[] b, String block, Shape shape) {
		int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
		int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
		int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					boolean edgeX = x == x0 || x == x1;
					boolean edgeZ = z == z0 || z == z1;
					boolean edgeY = y == y0 || y == y1;
					boolean include = switch (shape) {
						case SOLID -> true;
						case SHELL -> edgeX || edgeY || edgeZ;
						case WALLS -> edgeX || edgeZ;
					};
					if (include) {
						put(x, y, z, block);
					}
				}
			}
		}
	}

	private void line(int[] a, int[] b, String block) {
		int dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
		int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
		if (steps == 0) {
			put(a[0], a[1], a[2], block);
			return;
		}
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			put((int) Math.round(a[0] + dx * t), (int) Math.round(a[1] + dy * t), (int) Math.round(a[2] + dz * t), block);
		}
	}

	private void replace(int[] a, int[] b, String match, String block) {
		String matchId = RegionSnapshot.blockId(match);
		int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
		int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
		int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
		for (int y = Math.max(0, y0); y <= Math.min(region.sizeY - 1, y1); y++) {
			for (int z = Math.max(0, z0); z <= Math.min(region.sizeZ - 1, z1); z++) {
				for (int x = Math.max(0, x0); x <= Math.min(region.sizeX - 1, x1); x++) {
					if (RegionSnapshot.blockId(work[region.index(x, y, z)]).equals(matchId)) {
						put(x, y, z, block);
					}
				}
			}
		}
	}

	private void cylinder(int[] base, double radius, int height, String block, boolean hollow) {
		int r = (int) Math.ceil(radius);
		double outer = (radius + 0.5) * (radius + 0.5);
		double inner = (radius - 0.5) * (radius - 0.5);
		for (int y = base[1]; y < base[1] + height; y++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					double d = dx * dx + dz * dz;
					if (d <= outer && (!hollow || d >= inner)) {
						put(base[0] + dx, y, base[2] + dz, block);
					}
				}
			}
		}
	}

	private void sphere(int[] c, double radius, String block, boolean hollow) {
		int r = (int) Math.ceil(radius);
		double outer = (radius + 0.5) * (radius + 0.5);
		double inner = (radius - 0.5) * (radius - 0.5);
		for (int dy = -r; dy <= r; dy++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					double d = dx * dx + dy * dy + dz * dz;
					if (d <= outer && (!hollow || d >= inner)) {
						put(c[0] + dx, c[1] + dy, c[2] + dz, block);
					}
				}
			}
		}
	}

	private void put(int x, int y, int z, String block) {
		if (!region.contains(x, y, z)) {
			clipped = true;
			return;
		}
		work[region.index(x, y, z)] = block;
	}

	// --- JSON helpers -------------------------------------------------------------------------

	private static String str(JsonObject op, String key) throws ScriptException {
		if (!op.has(key) || !op.get(key).isJsonPrimitive()) {
			throw new ScriptException("missing \"" + key + "\"");
		}
		return op.get(key).getAsString();
	}

	private static double num(JsonObject op, String key) throws ScriptException {
		if (!op.has(key) || !op.get(key).isJsonPrimitive()) {
			throw new ScriptException("missing \"" + key + "\"");
		}
		double v = op.get(key).getAsDouble();
		if (v < 0 || v > 512) {
			throw new ScriptException("\"" + key + "\" out of range");
		}
		return v;
	}

	private static boolean bool(JsonObject op, String key) {
		return op.has(key) && op.get(key).isJsonPrimitive() && op.get(key).getAsBoolean();
	}

	private static int[] vec(JsonObject op, String key) throws ScriptException {
		if (!op.has(key) || !op.get(key).isJsonArray() || op.getAsJsonArray(key).size() != 3) {
			throw new ScriptException("\"" + key + "\" must be [x,y,z]");
		}
		JsonArray a = op.getAsJsonArray(key);
		return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
	}

	private static String block(JsonObject op) throws ScriptException {
		return normalize(str(op, "block"));
	}

	/** Adds the minecraft: namespace when missing and trims whitespace. */
	static String normalize(String state) throws ScriptException {
		String s = state.trim();
		if (s.isEmpty()) {
			throw new ScriptException("empty block");
		}
		String id = RegionSnapshot.blockId(s);
		if (!id.contains(":")) {
			s = "minecraft:" + s;
		}
		return s.toLowerCase(Locale.ROOT);
	}
}
