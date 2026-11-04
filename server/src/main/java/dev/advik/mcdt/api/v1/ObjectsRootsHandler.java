package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ReflectionDto.*;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TaskResult;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.reflection.NameService;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ObjectsRootsHandler implements Handler {
	private static final List<String> ROOT_NAMES = List.of(
			"minecraft", "player", "level", "screen", "options", "gameRenderer"
	);

	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final NameService nameService;

	public ObjectsRootsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient, NameService nameService) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
		this.nameService = nameService;
	}

	@OpenApi(
			path = "/v1/objects/roots",
			methods = {HttpMethod.GET},
			summary = "Root object handles",
			description = "Returns root handles for entry points into the game object graph: minecraft, player, level, screen, options, gameRenderer.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Root handles",
							content = {@OpenApiContent(from = RootsResponse.class)}
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
			Map<String, RootDescriptor> roots = new LinkedHashMap<>();
			for (String rootName : ROOT_NAMES) {
				Object obj = gameClient.getRootObject(rootName);
				if (obj != null) {
					String className = nameService.resolveClassName(obj.getClass().getName());
					roots.put(rootName, new RootDescriptor(rootName, className));
				} else {
					roots.put(rootName, null);
				}
			}
			return Json.MAPPER.valueToTree(new RootsResponse(roots));
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
