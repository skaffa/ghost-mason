package dev.ghostmason.core;

/**
 * The block states inside a box, as Minecraft block-state strings
 * (e.g. {@code minecraft:oak_stairs[facing=east,half=bottom,...]}).
 * Coordinates are relative to the box's minimum corner.
 */
public final class RegionSnapshot {
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	private final String[] states;

	public RegionSnapshot(int sizeX, int sizeY, int sizeZ, String[] states) {
		if (states.length != sizeX * sizeY * sizeZ) {
			throw new IllegalArgumentException("states length does not match size");
		}
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.states = states;
	}

	public int volume() {
		return states.length;
	}

	public boolean contains(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
	}

	public int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	public int xOf(int index) {
		return index % sizeX;
	}

	public int zOf(int index) {
		return (index / sizeX) % sizeZ;
	}

	public int yOf(int index) {
		return index / (sizeX * sizeZ);
	}

	public String get(int x, int y, int z) {
		return states[index(x, y, z)];
	}

	public String get(int index) {
		return states[index];
	}

	/** A copy of the raw state array, for building a modified version. */
	public String[] copyStates() {
		return states.clone();
	}

	public static boolean isAir(String state) {
		String id = blockId(state);
		return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
	}

	/** The block id without properties: {@code minecraft:oak_stairs[facing=east]} becomes {@code minecraft:oak_stairs}. */
	public static String blockId(String state) {
		int bracket = state.indexOf('[');
		return bracket < 0 ? state : state.substring(0, bracket);
	}
}
