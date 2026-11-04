package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

import java.util.List;

/**
 * Request and response structures for /v1/resourcepacks.
 */
public final class ResourcePacksDto {
	private ResourcePacksDto() {}

	@OpenApiName("ResourcePackInfo")
	public record PackInfo(
			String id,
			String title,
			String description,
			boolean isRequired,
			boolean isFixedPosition,
			String compatibility
	) {}

	@OpenApiName("ResourcePacksResponse")
	public record Response(
			List<PackInfo> available,
			List<String> enabled
	) {}

	@OpenApiName("ResourcePacksRequest")
	public record Request(
			List<String> enabled,
			Boolean reload
	) {}
}
