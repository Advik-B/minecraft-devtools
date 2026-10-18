package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

public final class ScreenActionDto {
	private ScreenActionDto() {}

	@OpenApiName("ScreenActionRequest")
	public record Request(
			String action,
			Integer widgetIndex,
			Double x,
			Double y,
			Integer button,
			String text
	) {}

	@OpenApiName("ScreenActionResponse")
	public record Response(
			boolean success,
			String action,
			boolean screenOpen,
			String screenClass,
			String screenTitle,
			long tick
	) {}
}
