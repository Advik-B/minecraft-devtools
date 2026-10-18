package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.BlocksDto;
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

public final class BlocksHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public BlocksHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/world/blocks",
			methods = {HttpMethod.GET},
			summary = "Query block states in a box",
			description = "Returns palette-indexed block states in a 3D bounding box capped at 32,768 blocks.",
			tags = {"World"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "from", required = true, description = "Starting coordinates as x,y,z"),
					@OpenApiParam(name = "to", required = true, description = "Ending coordinates as x,y,z")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Block states within bounds",
							content = {@OpenApiContent(from = BlocksDto.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Bad request - invalid coordinates or volume exceeds 32,768 blocks",
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
		String fromStr = ctx.queryParam("from");
		String toStr = ctx.queryParam("to");

		if (fromStr == null || toStr == null || fromStr.isBlank() || toStr.isBlank()) {
			throw Problems.invalidCoordinates("Query parameters 'from' and 'to' must be specified in 'x,y,z' format.");
		}

		int[] from = parseCoords(fromStr, "from");
		int[] to = parseCoords(toStr, "to");

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			BlocksDto response = gameClient.getBlocks(from[0], from[1], from[2], to[0], to[1], to[2]);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	private static int[] parseCoords(String str, String paramName) {
		String[] parts = str.split(",");
		if (parts.length != 3) {
			throw Problems.invalidCoordinates("Parameter '" + paramName + "' must have 3 comma-separated integers (x,y,z). Got: " + str);
		}
		try {
			return new int[]{
					Integer.parseInt(parts[0].trim()),
					Integer.parseInt(parts[1].trim()),
					Integer.parseInt(parts[2].trim())
			};
		} catch (NumberFormatException e) {
			throw Problems.invalidCoordinates("Parameter '" + paramName + "' contains invalid integer coordinate in: " + str);
		}
	}
}
