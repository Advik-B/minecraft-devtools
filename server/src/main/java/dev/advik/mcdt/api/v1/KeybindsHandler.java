package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.KeybindsDto;
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
 * Handler for GET, POST, and PATCH /v1/keybinds.
 */
public final class KeybindsHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public KeybindsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/keybinds",
			methods = {HttpMethod.GET},
			summary = "List all registered key mappings",
			description = "Returns key mapping name, category, currently bound key name, default key, and default/unbound flags.",
			tags = {"Options"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Keybinds list",
							content = {@OpenApiContent(from = KeybindsDto.Response.class)}
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
			KeybindsDto.Response response = gameClient.getKeybinds();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/keybinds",
			methods = {HttpMethod.POST, HttpMethod.PATCH},
			summary = "Rebind or reset key mappings",
			description = "Rebinds specified key mappings by name (rebinds map: name -> key), resets specific mappings (resetKeys), or resets all to default (resetAll).",
			tags = {"Options"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = KeybindsDto.Request.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Updated keybinds list",
							content = {@OpenApiContent(from = KeybindsDto.Response.class)}
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
		KeybindsDto.Request request;
		try {
			request = ctx.bodyAsClass(KeybindsDto.Request.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for keybinds update");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			KeybindsDto.Response response = gameClient.updateKeybinds(request);
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
