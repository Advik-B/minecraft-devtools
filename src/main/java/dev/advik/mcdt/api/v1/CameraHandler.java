package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.CameraDto;
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
 * Handler for GET, POST, and PATCH /v1/camera.
 */
public final class CameraHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public CameraHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/camera",
			methods = {HttpMethod.GET},
			summary = "Read camera perspective, FOV and orientation",
			description = "Returns current camera perspective (first_person, third_person_back, third_person_front), FOV, eye position, and rotation.",
			tags = {"Client"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Camera state",
							content = {@OpenApiContent(from = CameraDto.Response.class)}
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
			CameraDto.Response response = gameClient.getCamera();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/camera",
			methods = {HttpMethod.POST, HttpMethod.PATCH},
			summary = "Update camera perspective and FOV",
			description = "Sets camera perspective (first_person, third_person_back, third_person_front) and/or FOV setting (30-110).",
			tags = {"Client"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = CameraDto.Request.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Updated camera state",
							content = {@OpenApiContent(from = CameraDto.Response.class)}
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
		CameraDto.Request request;
		try {
			request = ctx.bodyAsClass(CameraDto.Request.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for camera update");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			CameraDto.Response response = gameClient.updateCamera(request);
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
