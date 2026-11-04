package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.EntitiesDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

public final class EntityCategoriesHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public EntityCategoriesHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/world/entities/categories",
			methods = {HttpMethod.GET},
			summary = "Get programmatic entity classifications",
			description = "Returns all registered entity types categorized into hostile, passive, player, and item categories.",
			tags = {"World"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Entity categories and classifications",
							content = {@OpenApiContent(from = EntitiesDto.CategoriesResponse.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
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
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			EntitiesDto.CategoriesResponse response = gameClient.getEntityCategories();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
