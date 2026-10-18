package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

public final class ScreenshotHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public ScreenshotHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/screenshot",
			methods = {HttpMethod.GET},
			summary = "Capture viewport screenshot",
			description = "Captures the current client viewport as a PNG image with optional scaling (default 0.5 for fast transmission).",
			tags = {"Client"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "scale", description = "Scale factor between 0.1 and 1.0 (default 0.5)")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "PNG screenshot bytes",
							content = {@OpenApiContent(type = "image/png")}
					),
					@OpenApiResponse(
							status = "400",
							description = "Bad request - invalid scale parameter",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "500",
							description = "Internal error encoding screenshot",
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
		float scale = 0.5f;
		String scaleStr = ctx.queryParam("scale");
		if (scaleStr != null && !scaleStr.isBlank()) {
			try {
				scale = Float.parseFloat(scaleStr);
				if (scale < 0.05f || scale > 2.0f) {
					throw Problems.invalidParameter("scale", "Scale must be between 0.1 and 1.0.");
				}
			} catch (NumberFormatException e) {
				throw Problems.invalidParameter("scale", "Scale must be a valid float: " + scaleStr);
			}
		}

		float finalScale = scale;
		long timeoutMs = config.requestTimeoutMs;

		// Note: Screenshot is a binary payload rather than JsonElement, so we submit with queue.clock().advance()
		CompletableFuture<byte[]> future = new CompletableFuture<>();
		try {
			queue.submit(() -> {
				try {
					byte[] pngBytes = gameClient.captureScreenshot(finalScale);
					future.complete(pngBytes);
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
				return dev.advik.mcdt.json.Json.MAPPER.nullNode();
			}, timeoutMs).exceptionally(ex -> {
				future.completeExceptionally(ex);
				return null;
			});
		} catch (Throwable t) {
			future.completeExceptionally(t);
		}

		ctx.future(() -> future.thenAccept(bytes -> {
			ctx.header("X-MCDT-Tick", String.valueOf(queue.clock().current()));
			ctx.contentType("image/png");
			ctx.result(bytes);
		}));
	}
}
