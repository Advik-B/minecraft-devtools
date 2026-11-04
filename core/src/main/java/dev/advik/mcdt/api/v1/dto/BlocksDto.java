package dev.advik.mcdt.api.v1.dto;

import java.util.List;

public record BlocksDto(
		BlockBounds bounds,
		List<String> palette,
		List<BlockEntry> blocks
) {
	public record BlockBounds(
			int[] min,
			int[] max,
			int volume
	) {}

	public record BlockEntry(
			int[] pos,
			int p
	) {}
}
