package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ResourcePacksDto;
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
 * Handler for GET, POST, and PATCH /v1/resourcepacks.
 */
public final class ResourcePacksHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public ResourcePacksHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/resourcepacks",
			methods = {HttpMethod.GET},
			summary = "List available and enabled resource packs",
			description = "Returns available resource packs with metadata (id, title, description, isRequired, isFixedPosition, compatibility) and ordered list of enabled pack IDs.",
			tags = {"Options"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Resource packs state",
							content = {@OpenApiContent(from = ResourcePacksDto.Response.class)}
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
			ResourcePacksDto.Response response = gameClient.getResourcePacks();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/resourcepacks",
			methods = {HttpMethod.POST, HttpMethod.PATCH},
			summary = "Enable, reorder, or reload resource packs",
			description = "Changes the enabled resource pack order (enabled list of IDs) and/or triggers a resource pack reload (reload: true).",
			tags = {"Options"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = ResourcePacksDto.Request.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Updated resource packs state",
							content = {@OpenApiContent(from = ResourcePacksDto.Response.class)}
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
		ResourcePacksDto.Request request;
		try {
			request = ctx.bodyAsClass(ResourcePacksDto.Request.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for resource packs update");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			ResourcePacksDto.Response response = gameClient.updateResourcePacks(request);
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
