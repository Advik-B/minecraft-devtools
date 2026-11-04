package dev.advik.mcdt.world.stream;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkOrderGeneratorTest {

	@Test
	void spiralOrderStartsAtCenterAndExpands() {
		List<ChunkOrderGenerator.ChunkOffset> offsets = ChunkOrderGenerator.generateSpiral(2);
		assertFalse(offsets.isEmpty());
		assertEquals(new ChunkOrderGenerator.ChunkOffset(0, 0), offsets.get(0));

		// Spiral radius 1 has 9 chunks (1 center + 8 surrounding)
		List<ChunkOrderGenerator.ChunkOffset> radius1 = ChunkOrderGenerator.generateSpiral(1);
		assertEquals(9, radius1.size());
	}

	@Test
	void radialOrderIsSortedByAscendingDistance() {
		List<ChunkOrderGenerator.ChunkOffset> offsets = ChunkOrderGenerator.generateRadial(3);
		assertFalse(offsets.isEmpty());
		assertEquals(new ChunkOrderGenerator.ChunkOffset(0, 0), offsets.get(0));

		double lastDistSq = 0;
		for (ChunkOrderGenerator.ChunkOffset offset : offsets) {
			double distSq = offset.dx() * offset.dx() + offset.dz() * offset.dz();
			assertTrue(distSq >= lastDistSq);
			lastDistSq = distSq;
		}
	}

	@Test
	void scanOrderGeneratesGrid() {
		List<ChunkOrderGenerator.ChunkOffset> offsets = ChunkOrderGenerator.generateScan(2);
		// Radius 2 grid is 5x5 = 25 chunks
		assertEquals(25, offsets.size());
		assertEquals(new ChunkOrderGenerator.ChunkOffset(-2, -2), offsets.get(0));
	}
}
