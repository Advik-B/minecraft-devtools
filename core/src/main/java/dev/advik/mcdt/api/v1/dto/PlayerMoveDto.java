package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

public final class PlayerMoveDto {
	private PlayerMoveDto() {}

	@OpenApiName("PlayerMoveRequest")
	public record Request(
			Boolean forward,
			Boolean backward,
			Boolean left,
			Boolean right,
			Boolean jump,
			Boolean sneak,
			Boolean sprint,
			Boolean fly,
			Boolean dismount,
			Integer ticks,
			Boolean stop
	) {}

	@OpenApiName("PlayerMoveResponse")
	public record Response(
			boolean forward,
			boolean backward,
			boolean left,
			boolean right,
			boolean jump,
			boolean sneak,
			boolean sprint,
			boolean flying,
			int remainingTicks,
			long tick
	) {}
}
