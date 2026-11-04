package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.WindowDto;
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

/**
 * Handler for GET, POST, and PATCH /v1/window.
 */
public final class WindowHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public WindowHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/window",
			methods = {HttpMethod.GET},
			summary = "Read window dimensions, scale, and focus state",
			description = "Returns window pixel size, GUI scaled dimensions, GUI scale factor, fullscreen flag, and focus state.",
			tags = {"Client"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Window state",
							content = {@OpenApiContent(from = WindowDto.Response.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
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
	public void get(Context ctx) {
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			WindowDto.Response response = gameClient.getWindow();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/window",
			methods = {HttpMethod.POST, HttpMethod.PATCH},
			summary = "Update window state (e.g. fullscreen toggle)",
			description = "Toggles or sets fullscreen mode for the client window.",
			tags = {"Client"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = WindowDto.Request.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Updated window state",
							content = {@OpenApiContent(from = WindowDto.Response.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Invalid parameter",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "403",
							description = "Permission denied",
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
	public void update(Context ctx) {
		WindowDto.Request request;
		try {
			request = ctx.bodyAsClass(WindowDto.Request.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for window update");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			WindowDto.Response response = gameClient.updateWindow(request);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@Override
	public void handle(Context ctx) {
		if ("GET".equalsIgnoreCase(ctx.method().name())) {
			get(ctx);
		} else {
			update(ctx);
		}
	}
}
