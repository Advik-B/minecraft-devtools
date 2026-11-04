package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.AdvancementsDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

/**
 * Handler for GET /v1/advancements.
 */
public final class AdvancementsHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public AdvancementsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/advancements",
			methods = {HttpMethod.GET},
			summary = "Read player advancements",
			description = "Returns the tree of loaded advancements and player completion progress, criteria, and display information.",
			tags = {"Player"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "done", type = Boolean.class, description = "Filter by completion status (true or false)"),
					@OpenApiParam(name = "search", description = "Filter advancements by id or title keyword")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Advancements list and progress",
							content = {@OpenApiContent(from = AdvancementsDto.Response.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "409",
							description = "No player loaded",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "503",
							description = "Queue full",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "504",
							description = "Timeout",
							content = {@OpenApiContent(from = Problem.class)}
					)
			}
	)
	@Override
	public void handle(Context ctx) {
		String doneStr = ctx.queryParam("done");
		Boolean done = doneStr != null ? Boolean.parseBoolean(doneStr) : null;
		String search = ctx.queryParam("search");

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			AdvancementsDto.Response response = gameClient.getAdvancements(done, search);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
