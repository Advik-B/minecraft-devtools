package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

import java.util.List;

/**
 * Request and response structures for /v1/debug.
 */
public final class DebugDto {
	private DebugDto() {}

	@OpenApiName("DebugResponse")
	public record Response(
			List<String> f3Left,
			List<String> f3Right,
			boolean hitboxes,
			boolean chunkBorders
	) {}

	@OpenApiName("DebugRequest")
	public record Request(
			Boolean hitboxes,
			Boolean chunkBorders
	) {}
}
