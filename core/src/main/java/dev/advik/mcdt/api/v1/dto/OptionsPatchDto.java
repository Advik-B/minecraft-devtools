package dev.advik.mcdt.api.v1.dto;

import java.util.Map;

public record OptionsPatchDto(
		Integer fov,
		Integer renderDistance,
		Integer simulationDistance,
		Integer guiScale,
		Double gamma,
		Integer maxFps,
		Boolean fullscreen,
		Boolean vsync,
		Boolean bobView,
		Double mouseSensitivity,
		Boolean invertYMouse,
		Boolean pauseOnLostFocus,
		Map<String, Float> soundVolumes,
		String language
) {}
