package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ScreenSlotDto;
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

public final class ScreenSlotsHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public ScreenSlotsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/screen/slots",
			methods = {HttpMethod.POST},
			summary = "Click container slot",
			description = "Perform container slot click actions: pickup, quick_move, swap, clone, throw, quick_craft, pickup_all.",
			tags = {"Screen"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = ScreenSlotDto.Request.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Slot clicked", content = {@OpenApiContent(from = ScreenSlotDto.Response.class)}),
					@OpenApiResponse(status = "400", description = "Invalid slot or click mode", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "409", description = "No container open", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		ScreenSlotDto.Request request;
		try {
			request = ctx.bodyAsClass(ScreenSlotDto.Request.class);
		} catch (Exception e) {
			throw Problems.invalidParameter("body", "Invalid request body: " + e.getMessage());
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			ScreenSlotDto.Response response = gameClient.clickScreenSlot(request);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
