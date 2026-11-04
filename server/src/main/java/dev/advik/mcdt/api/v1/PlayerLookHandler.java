package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.PlayerLookDto;
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

import java.util.concurrent.CompletableFuture;

public final class PlayerLookHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public PlayerLookHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/player/look",
			methods = {HttpMethod.POST},
			summary = "Set player rotation",
			description = "Set player yaw and pitch angles, apply relative deltas, or orient towards world coordinates.",
			tags = {"Player"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = PlayerLookDto.Request.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Look orientation updated", content = {@OpenApiContent(from = PlayerLookDto.Response.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "409", description = "No player loaded", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		PlayerLookDto.Request request;
		try {
			request = ctx.bodyAsClass(PlayerLookDto.Request.class);
		} catch (Exception e) {
			throw Problems.invalidParameter("body", "Invalid request body: " + e.getMessage());
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			PlayerLookDto.Response response = gameClient.setPlayerLook(request);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
