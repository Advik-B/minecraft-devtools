package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

public final class PlayerLookDto {
	private PlayerLookDto() {}

	@OpenApiName("PlayerLookRequest")
	public record Request(
			Float yaw,
			Float pitch,
			Float deltaYaw,
			Float deltaPitch,
			double[] target
	) {}

	@OpenApiName("PlayerLookResponse")
	public record Response(
			float yaw,
			float pitch,
			long tick
	) {}
}
