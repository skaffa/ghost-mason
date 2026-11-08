package dev.ghostmason.client;

import dev.ghostmason.core.RegionSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the current design as a Sponge schematic (version 3, .schem), the format WorldEdit,
 * FAWE and Litematica can load. The whole selection is saved, terrain included.
 */
final class SchematicExporter {
	private SchematicExporter() {
	}

	/** @return the written file */
	static Path export(String requestedName) throws IOException {
		RegionSnapshot snap = Session.snapshot;
		String[] design = Session.latest();
		String name = sanitize(requestedName);
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		Files.createDirectories(dir);
		Path file = dir.resolve(name + ".schem");

		// Saved as placed: after /gm rotate the schematic is rotated too.
		int width = Session.placedSizeX();
		int length = Session.placedSizeZ();
		var rotation = Session.rotation();
		String[] placed = new String[snap.volume()];
		Map<String, String> canonical = new HashMap<>();
		for (int i = 0; i < snap.volume(); i++) {
			int[] xz = Session.rotatedXZ(snap.xOf(i), snap.zOf(i));
			// Sponge v3 order: index = x + z * width + y * width * length.
			int out = xz[0] + xz[1] * width + snap.yOf(i) * width * length;
			placed[out] = canonical.computeIfAbsent(design[i], s -> {
				BlockState parsed = Planner.parseState(s);
				return parsed == null ? "minecraft:air" : BlockStateParser.serialize(parsed.rotate(rotation));
			});
		}
		Map<String, Integer> palette = new LinkedHashMap<>();
		ByteArrayOutputStream data = new ByteArrayOutputStream(snap.volume());
		for (String key : placed) {
			writeVarInt(data, palette.computeIfAbsent(key, k -> palette.size()));
		}

		CompoundTag paletteTag = new CompoundTag();
		palette.forEach(paletteTag::putInt);
		CompoundTag blocks = new CompoundTag();
		blocks.put("Palette", paletteTag);
		blocks.putByteArray("Data", data.toByteArray());
		blocks.put("BlockEntities", new ListTag());

		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", name);
		metadata.putString("Author", "Ghost Mason");
		metadata.putLong("Date", System.currentTimeMillis());

		CompoundTag schematic = new CompoundTag();
		schematic.putInt("Version", 3);
		schematic.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		schematic.putShort("Width", (short) width);
		schematic.putShort("Height", (short) snap.sizeY);
		schematic.putShort("Length", (short) length);
		schematic.putIntArray("Offset", new int[]{0, 0, 0});
		schematic.put("Metadata", metadata);
		schematic.put("Blocks", blocks);

		CompoundTag root = new CompoundTag();
		root.put("Schematic", schematic);
		NbtIo.writeCompressed(root, file);
		return file;
	}

	static String defaultName() {
		return "ghostmason-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
	}

	private static String sanitize(String name) {
		String clean = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
		if (clean.isEmpty()) {
			return defaultName();
		}
		return clean.length() > 64 ? clean.substring(0, 64) : clean;
	}

	private static void writeVarInt(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}
}
