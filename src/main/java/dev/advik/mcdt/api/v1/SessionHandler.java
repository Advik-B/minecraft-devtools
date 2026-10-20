package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.SessionDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

/**
 * Handler for /v1/session routes.
 */
public final class SessionHandler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public SessionHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/session",
			methods = {HttpMethod.GET},
			summary = "Read active session state",
			description = "Returns current session status (menu, singleplayer, multiplayer), pause state, LAN status, and server/world info.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Current session state",
							content = {@OpenApiContent(from = SessionDto.Response.class)}
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
			SessionDto.Response response = gameClient.getSession();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/session/pause",
			methods = {HttpMethod.POST},
			summary = "Pause or unpause singleplayer game",
			description = "Pauses or unpauses the game if running in a pauseable singleplayer world.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					content = {@OpenApiContent(from = SessionDto.PauseRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Updated session state",
							content = {@OpenApiContent(from = SessionDto.Response.class)}
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
	public void pause(Context ctx) {
		SessionDto.PauseRequest request;
		try {
			request = ctx.bodyAsClass(SessionDto.PauseRequest.class);
		} catch (Exception e) {
			request = new SessionDto.PauseRequest(true);
		}

		final Boolean paused = (request != null && request.paused() != null) ? request.paused() : true;
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			SessionDto.Response response = gameClient.setSessionPaused(paused);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/session/disconnect",
			methods = {HttpMethod.POST},
			summary = "Disconnect from current world or server",
			description = "Disconnects from the active singleplayer world or multiplayer server and returns the client to the title screen.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Disconnected",
							content = {@OpenApiContent(from = SessionDto.DisconnectResponse.class)}
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
	public void disconnect(Context ctx) {
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			SessionDto.DisconnectResponse response = gameClient.disconnectSession();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/session/lan",
			methods = {HttpMethod.POST},
			summary = "Open singleplayer game to LAN",
			description = "Publishes the currently running singleplayer world as a LAN server.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					content = {@OpenApiContent(from = SessionDto.LanRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "LAN opened",
							content = {@OpenApiContent(from = SessionDto.LanResponse.class)}
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
							status = "409",
							description = "No singleplayer world loaded",
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
	public void lan(Context ctx) {
		SessionDto.LanRequest request;
		try {
			request = ctx.bodyAsClass(SessionDto.LanRequest.class);
		} catch (Exception e) {
			request = new SessionDto.LanRequest(null, null, null);
		}

		final SessionDto.LanRequest req = request;
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			SessionDto.LanResponse response = gameClient.openSessionToLan(req);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
