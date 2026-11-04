package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ClientDto(
		int fps,
		WindowInfo window,
		ScreenSummary screen,
		String connectionState,
		@JsonInclude(JsonInclude.Include.NON_NULL) ServerInfo server
) {
	public record WindowInfo(
			int width,
			int height,
			int guiScaledWidth,
			int guiScaledHeight,
			double guiScale,
			boolean fullscreen,
			boolean focused
	) {}

	public record ScreenSummary(
			boolean open,
			@JsonInclude(JsonInclude.Include.NON_NULL) String className,
			@JsonInclude(JsonInclude.Include.NON_NULL) String title
	) {}

	public record ServerInfo(
			String address,
			long ping,
			String version,
			String motd,
			boolean lan
	) {}
}
