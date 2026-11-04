package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

import java.util.List;
import java.util.Map;

/**
 * Request and response structures for /v1/keybinds.
 */
public final class KeybindsDto {
	private KeybindsDto() {}

	@OpenApiName("KeyMappingDetail")
	public record KeyMappingDetail(
			String name,
			String category,
			String boundKey,
			String defaultKey,
			boolean isDefault,
			boolean isUnbound
	) {}

	@OpenApiName("KeybindsResponse")
	public record Response(
			int total,
			List<KeyMappingDetail> keybinds
	) {}

	@OpenApiName("KeybindsRequest")
	public record Request(
			Map<String, String> rebinds,
			Boolean resetAll,
			List<String> resetKeys
	) {}
}
