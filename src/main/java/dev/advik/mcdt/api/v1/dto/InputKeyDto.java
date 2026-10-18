package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.util.List;

public final class InputKeyDto {
	private InputKeyDto() {}

	@OpenApiName("InputKeyRequest")
	public record Request(
			String key,
			Integer keyCode,
			Integer scanCode,
			String action,
			Integer durationTicks,
			List<String> modifiers,
			String text
	) {}

	@OpenApiName("InputKeyResponse")
	public record Response(
			boolean success,
			String action,
			String key,
			Integer keyCode,
			long tick
	) {}
}
