package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

/**
 * DTO for /v1/session.
 */
public final class SessionDto {
	private SessionDto() {}

	@OpenApiName("SessionResponse")
	public record Response(
			String state,
			boolean paused,
			boolean lan,
			Integer lanPort,
			String serverAddress,
			String serverName,
			String worldName
	) {}

	@OpenApiName("SessionPauseRequest")
	public record PauseRequest(
			Boolean paused
	) {}

	@OpenApiName("SessionLanRequest")
	public record LanRequest(
			Integer port,
			String gameMode,
			Boolean allowCommands
	) {}

	@OpenApiName("SessionLanResponse")
	public record LanResponse(
			boolean success,
			int port
	) {}

	@OpenApiName("SessionDisconnectResponse")
	public record DisconnectResponse(
			boolean success
	) {}
}
