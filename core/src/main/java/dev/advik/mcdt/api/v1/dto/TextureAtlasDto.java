package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TextureAtlasDto(
		String atlasId,
		int width,
		int height,
		int spriteCount,
		Map<String, SpriteInfo> sprites,
		String imageBase64
) {
	public record SpriteInfo(
			int x,
			int y,
			int width,
			int height,
			float u0,
			float u1,
			float v0,
			float v1,
			boolean animated,
			int frameCount
	) {}
}
