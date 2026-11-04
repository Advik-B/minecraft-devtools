package dev.advik.mcdt.world.stream;

import java.util.List;

/**
 * Plain snapshots of world state for the 3D viewer stream. {@link dev.advik.mcdt.game.GameClient} builds them on the
 * client thread; {@link BinaryChunkSerializer} encodes them anywhere.
 */
public final class ViewData {
	private ViewData() {}

	/** The loaded level. {@code levelId} changes whenever the client switches world or dimension. */
	public record Level(int levelId, String dimension, int minY, int height, boolean hasSkyLight) {}

	/**
	 * One 16³ section. {@code palette} holds global block state ids. {@code indices} is 4096 palette indices in
	 * {@code y<<8 | z<<4 | x} order, or null when the palette has a single entry. {@code skyLight} and
	 * {@code blockLight} are vanilla 2048-byte nibble arrays, or null for the default: sky 15 (0 without sky light), block 0.
	 */
	public record Section(int sectionY, int[] palette, short[] indices, byte[] skyLight, byte[] blockLight) {}

	/**
	 * A chunk column. When {@code full} is true the client replaces every section of the column (missing ones are air);
	 * otherwise only the listed sections change. The tint arrays hold 256 RGB colors in {@code z<<4 | x} order, or null.
	 */
	public record Chunk(int chunkX, int chunkZ, boolean full, List<Section> sections, int[] grassTint, int[] foliageTint, int[] waterTint) {}

	public record Pose(double x, double y, double z, float yaw, float pitch, long dayTime, float skyBrightness) {}

	/** {@code category}: 0 misc, 1 player, 2 hostile, 3 passive, 4 item, 5 projectile/vehicle. */
	public record Entity(int id, String type, int category, double x, double y, double z, float yaw, float width, float height, boolean self, String name) {}
}
