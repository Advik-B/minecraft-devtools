package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

public final class PlayerActionDto {
	private PlayerActionDto() {}

	@OpenApiName("PlayerActionRequest")
	public record Request(
			String action,
			String hand,
			Integer slot,
			Boolean all,
			int[] pos,
			String face,
			Integer entityId,
			String interactionType
	) {}

	@OpenApiName("PlayerActionResponse")
	public record Response(
			String action,
			boolean success,
			String detail,
			long tick
	) {}
}
