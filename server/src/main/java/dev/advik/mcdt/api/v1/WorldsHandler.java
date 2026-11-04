package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.WorldsDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.ConfirmManager;
import io.javalin.http.Context;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

/**
 * Handler for /v1/worlds routes.
 */
public final class WorldsHandler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final ConfirmManager confirmManager;

	public WorldsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient, ConfirmManager confirmManager) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
		this.confirmManager = confirmManager;
	}

	@OpenApi(
			path = "/v1/worlds",
			methods = {HttpMethod.GET},
			summary = "List saved singleplayer worlds",
			description = "Returns summary information for all saved singleplayer worlds available on disk.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "List of singleplayer worlds",
							content = {@OpenApiContent(from = WorldsDto.ListResponse.class)}
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
	public void list(Context ctx) {
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			WorldsDto.ListResponse response = gameClient.getWorlds();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/worlds/{levelId}/load",
			methods = {HttpMethod.POST},
			summary = "Load and open a singleplayer world",
			description = "Loads the singleplayer world identified by levelId (folder name).",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "levelId", description = "Target world folder identifier", required = true)
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "World load initiated",
							content = {@OpenApiContent(from = WorldsDto.LoadResponse.class)}
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
	public void load(Context ctx) {
		String levelId = ctx.pathParam("levelId");
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			WorldsDto.LoadResponse response = gameClient.loadWorld(levelId);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/worlds/{levelId}",
			methods = {HttpMethod.DELETE},
			summary = "Request world deletion confirmation token",
			description = "Initiates two-step confirmation to delete a singleplayer world. Returns a single-use token valid for 30 seconds.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "levelId", description = "Target world folder identifier", required = true)
			},
			responses = {
					@OpenApiResponse(
							status = "202",
							description = "Confirmation token generated",
							content = {@OpenApiContent(from = WorldsDto.DeleteTokenResponse.class)}
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
					)
			}
	)
	public void deleteRequest(Context ctx) {
		String levelId = ctx.pathParam("levelId");
		String auth = ctx.header("Authorization");
		String bearer = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : "";

		ConfirmManager.TokenInfo tokenInfo = confirmManager.createToken(bearer, "delete_world:" + levelId);
		WorldsDto.DeleteTokenResponse response = new WorldsDto.DeleteTokenResponse(tokenInfo.token(), tokenInfo.expiresAt());

		ctx.status(202);
		ctx.contentType("application/json");
		try {
			ctx.result(Json.MAPPER.writeValueAsString(response));
		} catch (Exception e) {
			throw Problems.internal("Failed to serialize world delete response: " + e.getMessage());
		}
	}

	@OpenApi(
			path = "/v1/worlds/{levelId}/delete/confirm",
			methods = {HttpMethod.POST},
			summary = "Confirm world deletion",
			description = "Permanently deletes the singleplayer world using the confirmation token from step 1.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "levelId", description = "Target world folder identifier", required = true)
			},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = WorldsDto.DeleteConfirmRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "World deleted",
							content = {@OpenApiContent(from = WorldsDto.DeleteResponse.class)}
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
							status = "404",
							description = "World not found",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "409",
							description = "Invalid or expired confirmation token",
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
	public void deleteConfirm(Context ctx) {
		String levelId = ctx.pathParam("levelId");
		WorldsDto.DeleteConfirmRequest request;
		try {
			request = ctx.bodyAsClass(WorldsDto.DeleteConfirmRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for world delete confirmation");
		}

		String auth = ctx.header("Authorization");
		String bearer = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : "";
		confirmManager.validateAndConsume(request.confirmToken(), bearer, "delete_world:" + levelId);

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			WorldsDto.DeleteResponse response = gameClient.deleteWorld(levelId);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
