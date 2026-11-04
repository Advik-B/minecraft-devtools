package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.util.List;

/**
 * DTO for /v1/servers.
 */
public final class ServersDto {
	private ServersDto() {}

	@OpenApiName("ServerEntryInfo")
	public record ServerEntryInfo(
			int index,
			String name,
			String address,
			long ping,
			String status,
			String playerCount
	) {}

	@OpenApiName("ServersListResponse")
	public record ListResponse(
			int total,
			List<ServerEntryInfo> servers
	) {}

	@OpenApiName("ServerAddRequest")
	public record AddRequest(
			String name,
			String address
	) {}

	@OpenApiName("ServerUpdateRequest")
	public record UpdateRequest(
			String name,
			String address
	) {}

	@OpenApiName("ServerConnectRequest")
	public record ConnectRequest(
			String address
	) {}

	@OpenApiName("ServerConnectResponse")
	public record ConnectResponse(
			boolean success,
			String address
	) {}

	@OpenApiName("ServerDeleteConfirmRequest")
	public record DeleteConfirmRequest(
			String confirmToken
	) {}

	@OpenApiName("ServerDeleteTokenResponse")
	public record DeleteTokenResponse(
			String confirmToken,
			java.time.Instant expiresAt
	) {}

	@OpenApiName("ServerDeleteResponse")
	public record DeleteResponse(
			boolean success,
			int index
	) {}
}
