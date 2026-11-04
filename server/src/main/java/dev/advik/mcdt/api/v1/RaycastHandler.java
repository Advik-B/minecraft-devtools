package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.RaycastDto;
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

public final class RaycastHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public RaycastHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/world/raycast",
			methods = {HttpMethod.GET},
			summary = "Raycast or targeting query",
			description = "Returns crosshair targeting hit by default, or casts an arbitrary ray from coordinates in a direction.",
			tags = {"World"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "from", description = "Ray origin as x,y,z (defaults to player eye position)"),
					@OpenApiParam(name = "direction", description = "Ray direction vector as dx,dy,dz (defaults to player view vector)"),
					@OpenApiParam(name = "maxDistance", description = "Maximum raycast distance (default 20.0, max 128.0)"),
					@OpenApiParam(name = "includeFluids", description = "Whether to hit fluid blocks (default false)"),
					@OpenApiParam(name = "includeEntities", description = "Whether to test entity hitboxes (default true)")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Raycast result",
							content = {@OpenApiContent(from = RaycastDto.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Bad request - invalid raycast parameters",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "409",
							description = "No world loaded",
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
		double[] from = parseDoubles(ctx.queryParam("from"), "from");
		double[] direction = parseDoubles(ctx.queryParam("direction"), "direction");

		Double maxDistance = null;
		String distStr = ctx.queryParam("maxDistance");
		if (distStr != null && !distStr.isBlank()) {
			try {
				maxDistance = Double.parseDouble(distStr);
			} catch (NumberFormatException e) {
				throw Problems.invalidParameter("maxDistance", "maxDistance must be a valid number: " + distStr);
			}
		}

		Boolean includeFluids = null;
		String fluidsStr = ctx.queryParam("includeFluids");
		if (fluidsStr != null && !fluidsStr.isBlank()) {
			includeFluids = Boolean.parseBoolean(fluidsStr);
		}

		Boolean includeEntities = null;
		String entitiesStr = ctx.queryParam("includeEntities");
		if (entitiesStr != null && !entitiesStr.isBlank()) {
			includeEntities = Boolean.parseBoolean(entitiesStr);
		}

		Double finalDist = maxDistance;
		Boolean finalFluids = includeFluids;
		Boolean finalEntities = includeEntities;

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			RaycastDto response = gameClient.raycast(from, direction, finalDist, finalFluids, finalEntities);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	private static double[] parseDoubles(String str, String paramName) {
		if (str == null || str.isBlank()) {
			return null;
		}
		String[] parts = str.split(",");
		if (parts.length != 3) {
			throw Problems.invalidCoordinates("Parameter '" + paramName + "' must be 3 comma-separated numbers (x,y,z). Got: " + str);
		}
		try {
			return new double[]{
					Double.parseDouble(parts[0].trim()),
					Double.parseDouble(parts[1].trim()),
					Double.parseDouble(parts[2].trim())
			};
		} catch (NumberFormatException e) {
			throw Problems.invalidCoordinates("Parameter '" + paramName + "' contains invalid number in: " + str);
		}
	}
}
