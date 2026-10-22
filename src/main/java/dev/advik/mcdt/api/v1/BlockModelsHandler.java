package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.BlockModelsDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BlockModelsHandler implements Handler {
	static final int MAX_IDS = 4096;

	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public BlockModelsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/world/block-models",
			methods = {HttpMethod.POST},
			summary = "Resolve baked block geometry",
			description = "Returns the baked model quads, render layer, face occlusion, tint source and fluid data for global block state ids, "
					+ "as streamed in /v1/world/stream palettes. Unknown ids are omitted.",
			tags = {"World"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = BlockModelsDto.Request.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Block geometry by state id", content = {@OpenApiContent(from = BlockModelsDto.Response.class)}),
					@OpenApiResponse(status = "400", description = "Invalid request body", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		BlockModelsDto.Request request;
		try {
			request = ctx.bodyAsClass(BlockModelsDto.Request.class);
		} catch (Exception e) {
			throw Problems.invalidParameter("body", "Invalid request body: " + e.getMessage());
		}
		List<Integer> ids = request != null ? request.ids() : null;
		if (ids == null || ids.isEmpty()) {
			throw Problems.invalidParameter("ids", "ids must be a non-empty array of block state ids");
		}
		if (ids.size() > MAX_IDS) {
			throw Problems.invalidParameter("ids", "At most " + MAX_IDS + " ids per request");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			BlockModelsDto.Response response = gameClient.getBlockModels(ids);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
