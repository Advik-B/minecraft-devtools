package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.RegistriesDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

public final class RegistryEntriesHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public RegistryEntriesHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/registries/{registry}",
			methods = {HttpMethod.GET},
			summary = "List entries in a registry",
			description = "Returns paginated entry identifiers in a specified registry, with optional search query filter.",
			tags = {"Registries"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "registry", required = true, description = "Registry identifier, e.g. minecraft:item")
			},
			queryParams = {
					@OpenApiParam(name = "query", description = "Substring search query"),
					@OpenApiParam(name = "offset", description = "Pagination offset (default 0)"),
					@OpenApiParam(name = "limit", description = "Pagination limit (default 100, max 1000)")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Registry entries",
							content = {@OpenApiContent(from = RegistriesDto.EntryResponse.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Bad request - invalid offset or limit",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "404",
							description = "Registry not found",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "503",
							description = "Task queue full",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "504",
							description = "Gateway timeout",
							content = {@OpenApiContent(from = Problem.class)}
					)
			}
	)
	@Override
	public void handle(Context ctx) {
		String registry = ctx.pathParam("registry");
		String query = ctx.queryParam("query");

		int offset = 0;
		String offsetStr = ctx.queryParam("offset");
		if (offsetStr != null && !offsetStr.isBlank()) {
			try {
				offset = Integer.parseInt(offsetStr);
				if (offset < 0) {
					throw Problems.invalidParameter("offset", "Offset must be non-negative.");
				}
			} catch (NumberFormatException e) {
				throw Problems.invalidParameter("offset", "Offset must be a valid integer: " + offsetStr);
			}
		}

		int limit = 100;
		String limitStr = ctx.queryParam("limit");
		if (limitStr != null && !limitStr.isBlank()) {
			try {
				limit = Integer.parseInt(limitStr);
				if (limit <= 0 || limit > 1000) {
					throw Problems.invalidParameter("limit", "Limit must be between 1 and 1000.");
				}
			} catch (NumberFormatException e) {
				throw Problems.invalidParameter("limit", "Limit must be a valid integer: " + limitStr);
			}
		}

		int finalOffset = offset;
		int finalLimit = limit;

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			RegistriesDto.EntryResponse response = gameClient.getRegistryEntries(registry, query, finalOffset, finalLimit);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
