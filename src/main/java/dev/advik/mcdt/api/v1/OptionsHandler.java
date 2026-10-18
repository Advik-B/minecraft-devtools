package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.OptionsDto;
import dev.advik.mcdt.api.v1.dto.OptionsPatchDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

import java.util.concurrent.CompletableFuture;

public final class OptionsHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public OptionsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/options",
			methods = {HttpMethod.GET},
			summary = "Game options and settings",
			description = "Returns game options including FOV, render distance, GUI scale, audio volumes, key mappings, and language.",
			tags = {"Options"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Game options",
							content = {@OpenApiContent(from = OptionsDto.class)}
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
	@OpenApi(
			path = "/v1/options",
			methods = {HttpMethod.PATCH},
			summary = "Change game options and settings",
			description = "Updates game options such as FOV, render distance, GUI scale, audio volumes, and saves changes.",
			tags = {"Options"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = OptionsPatchDto.class)}),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Updated game options",
							content = {@OpenApiContent(from = OptionsDto.class)}
					),
					@OpenApiResponse(status = "400", description = "Invalid parameter", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		long timeoutMs = config.requestTimeoutMs;

		if (ctx.method() == HandlerType.PATCH) {
			OptionsPatchDto patchRequest;
			try {
				patchRequest = ctx.bodyAsClass(OptionsPatchDto.class);
			} catch (Exception e) {
				throw Problems.invalidParameter("body", "Invalid request body: " + e.getMessage());
			}

			CompletableFuture<Void> future = queue.submit(() -> {
				OptionsDto response = gameClient.patchOptions(patchRequest);
				return Json.MAPPER.valueToTree(response);
			}, timeoutMs).thenAccept(result -> {
				ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
				ctx.contentType("application/json");
				ctx.result(result.body().toString());
			});
			ctx.future(() -> future);
		} else {
			CompletableFuture<Void> future = queue.submit(() -> {
				OptionsDto response = gameClient.getOptions();
				return Json.MAPPER.valueToTree(response);
			}, timeoutMs).thenAccept(result -> {
				ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
				ctx.contentType("application/json");
				ctx.result(result.body().toString());
			});
			ctx.future(() -> future);
		}
	}
}
