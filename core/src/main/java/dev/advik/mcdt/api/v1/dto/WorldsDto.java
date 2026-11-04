package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.util.List;

/**
 * DTO for /v1/worlds.
 */
public final class WorldsDto {
	private WorldsDto() {}

	@OpenApiName("WorldSummaryInfo")
	public record WorldSummaryInfo(
			String levelId,
			String levelName,
			long lastPlayed,
			String gameMode,
			boolean hardcore,
			boolean commands,
			String versionName,
			boolean locked
	) {}

	@OpenApiName("WorldsListResponse")
	public record ListResponse(
			int total,
			List<WorldSummaryInfo> worlds
	) {}

	@OpenApiName("WorldLoadRequest")
	public record LoadRequest(
			String levelId
	) {}

	@OpenApiName("WorldLoadResponse")
	public record LoadResponse(
			boolean success,
			String levelId
	) {}

	@OpenApiName("WorldDeleteConfirmRequest")
	public record DeleteConfirmRequest(
			String confirmToken
	) {}

	@OpenApiName("WorldDeleteTokenResponse")
	public record DeleteTokenResponse(
			String confirmToken,
			java.time.Instant expiresAt
	) {}

	@OpenApiName("WorldDeleteResponse")
	public record DeleteResponse(
			boolean success,
			String levelId
	) {}
}
