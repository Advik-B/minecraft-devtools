package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

public final class InputMouseDto {
	private InputMouseDto() {}

	@OpenApiName("InputMouseRequest")
	public record Request(
			String action,
			String button,
			Integer buttonCode,
			Double x,
			Double y,
			Double dx,
			Double dy,
			Double scrollX,
			Double scrollY,
			Integer clickCount
	) {}

	@OpenApiName("InputMouseResponse")
	public record Response(
			boolean success,
			String action,
			double x,
			double y,
			long tick
	) {}
}
