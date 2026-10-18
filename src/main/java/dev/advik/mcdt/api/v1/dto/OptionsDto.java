package dev.advik.mcdt.api.v1.dto;

import java.util.List;
import java.util.Map;

public record OptionsDto(
		int fov,
		int renderDistance,
		int simulationDistance,
		int guiScale,
		double gamma,
		int maxFps,
		boolean fullscreen,
		boolean vsync,
		String graphicsMode,
		boolean bobView,
		double mouseSensitivity,
		boolean invertYMouse,
		Map<String, Float> soundVolumes,
		List<KeyMappingInfo> keyMappings,
		String language
) {
	public record KeyMappingInfo(
			String name,
			String category,
			String key,
			String boundKey
	) {}
}
