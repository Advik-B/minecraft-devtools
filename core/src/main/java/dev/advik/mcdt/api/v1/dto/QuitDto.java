package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.time.Instant;

public final class QuitDto {
	private QuitDto() {}

	@OpenApiName("QuitRequestResponse")
	public record RequestResponse(
			String confirmToken,
			Instant expiresAt
	) {}

	@OpenApiName("QuitConfirmRequest")
	public record ConfirmRequest(
			String confirmToken
	) {}

	@OpenApiName("QuitConfirmResponse")
	public record ConfirmResponse(
			String status,
			String message
	) {}
}
