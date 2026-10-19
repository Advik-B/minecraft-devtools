package dev.advik.mcdt.api.v1.dto;

import java.util.List;

public record TextureAtlasesDto(
		int total,
		List<AtlasSummary> atlases
) {
	public record AtlasSummary(
			String id,
			int width,
			int height,
			int spriteCount
	) {}
}
