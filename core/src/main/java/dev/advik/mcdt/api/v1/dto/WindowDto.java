package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

/**
 * Request and response structures for /v1/window.
 */
public final class WindowDto {
	private WindowDto() {}

	@OpenApiName("WindowResponse")
	public record Response(
			int width,
			int height,
			int guiScaledWidth,
			int guiScaledHeight,
			double guiScale,
			boolean fullscreen,
			boolean focused
	) {}

	@OpenApiName("WindowRequest")
	public record Request(
			Boolean fullscreen
	) {}
}
