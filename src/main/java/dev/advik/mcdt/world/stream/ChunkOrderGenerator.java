package dev.advik.mcdt.world.stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates chunk offset coordinates (dx, dz) for streaming to 3D viewers in various orderings.
 */
public final class ChunkOrderGenerator {
	public record ChunkOffset(int dx, int dz) {}

	public enum Order {
		SPIRAL,
		RADIAL,
		SCAN
	}

	public static Order parseOrder(String orderStr) {
		if (orderStr == null) return Order.SPIRAL;
		try {
			return Order.valueOf(orderStr.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return Order.SPIRAL;
		}
	}

	public static List<ChunkOffset> generate(int radius, Order order) {
		radius = Math.max(1, Math.min(16, radius));
		return switch (order) {
			case SPIRAL -> generateSpiral(radius);
			case RADIAL -> generateRadial(radius);
			case SCAN -> generateScan(radius);
		};
	}

	/**
	 * Generates chunks spiraling outward from (0, 0).
	 */
	public static List<ChunkOffset> generateSpiral(int radius) {
		List<ChunkOffset> result = new ArrayList<>();
		result.add(new ChunkOffset(0, 0));

		for (int r = 1; r <= radius; r++) {
			int x = -r;
			int z = -r;

			// Top edge: (-r, -r) -> (r, -r)
			for (; x < r; x++) result.add(new ChunkOffset(x, z));
			// Right edge: (r, -r) -> (r, r)
			for (; z < r; z++) result.add(new ChunkOffset(x, z));
			// Bottom edge: (r, r) -> (-r, r)
			for (; x > -r; x--) result.add(new ChunkOffset(x, z));
			// Left edge: (-r, r) -> (-r, -r)
			for (; z > -r; z--) result.add(new ChunkOffset(x, z));
		}

		return result;
	}

	/**
	 * Generates chunks sorted by ascending Euclidean distance from (0, 0).
	 */
	public static List<ChunkOffset> generateRadial(int radius) {
		List<ChunkOffset> result = new ArrayList<>();
		int rSquared = radius * radius;

		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				if (dx * dx + dz * dz <= rSquared) {
					result.add(new ChunkOffset(dx, dz));
				}
			}
		}

		result.sort(Comparator.comparingDouble(o -> (o.dx * o.dx + o.dz * o.dz)));
		return result;
	}

	/**
	 * Generates chunks in simple row-by-row scan line order.
	 */
	public static List<ChunkOffset> generateScan(int radius) {
		List<ChunkOffset> result = new ArrayList<>();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				result.add(new ChunkOffset(dx, dz));
			}
		}
		return result;
	}
}
