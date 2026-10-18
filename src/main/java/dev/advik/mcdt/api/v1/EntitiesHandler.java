package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.EntitiesDto;
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

public final class EntitiesHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public EntitiesHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/world/entities",
			methods = {HttpMethod.GET},
			summary = "Query entities near the player or coordinates",
			description = "Returns nearby entities within a radius (up to 128 blocks), filterable by entity type.",
			tags = {"World"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "radius", description = "Search radius in blocks (default 32.0, max 128.0)"),
					@OpenApiParam(name = "type", description = "Entity type identifier filter, e.g. minecraft:zombie"),
					@OpenApiParam(name = "pos", description = "Center position as x,y,z (defaults to player position)"),
					@OpenApiParam(name = "limit", description = "Maximum entities to return (default 100, max 500)")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Nearby entities",
							content = {@OpenApiContent(from = EntitiesDto.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Bad request - invalid radius, coordinates or limit",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "409",
							description = "No world or player loaded",
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
		Double radius = null;
		String radiusStr = ctx.queryParam("radius");
		if (radiusStr != null && !radiusStr.isBlank()) {
			try {
				radius = Double.parseDouble(radiusStr);
			} catch (NumberFormatException e) {
				throw Problems.invalidParameter("radius", "Radius must be a valid number: " + radiusStr);
			}
		}

		String typeFilter = ctx.queryParam("type");

		double[] center = null;
		String posStr = ctx.queryParam("pos");
		if (posStr != null && !posStr.isBlank()) {
			String[] parts = posStr.split(",");
			if (parts.length != 3) {
				throw Problems.invalidCoordinates("Parameter 'pos' must be 3 comma-separated numbers (x,y,z). Got: " + posStr);
			}
			try {
				center = new double[]{
						Double.parseDouble(parts[0].trim()),
						Double.parseDouble(parts[1].trim()),
						Double.parseDouble(parts[2].trim())
				};
			} catch (NumberFormatException e) {
				throw Problems.invalidCoordinates("Parameter 'pos' contains invalid number in: " + posStr);
			}
		}

		Integer limit = null;
		String limitStr = ctx.queryParam("limit");
		if (limitStr != null && !limitStr.isBlank()) {
			try {
				limit = Integer.parseInt(limitStr);
			} catch (NumberFormatException e) {
				throw Problems.invalidParameter("limit", "Limit must be a valid integer: " + limitStr);
			}
		}

		Double finalRadius = radius;
		double[] finalCenter = center;
		Integer finalLimit = limit;

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			EntitiesDto response = gameClient.getEntities(finalRadius, typeFilter, finalCenter, finalLimit);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
