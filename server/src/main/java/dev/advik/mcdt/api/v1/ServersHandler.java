package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ServersDto;
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
 * Handler for /v1/servers routes.
 */
public final class ServersHandler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final ConfirmManager confirmManager;

	public ServersHandler(McdtConfig config, TaskQueue queue, GameClient gameClient, ConfirmManager confirmManager) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
		this.confirmManager = confirmManager;
	}

	@OpenApi(
			path = "/v1/servers",
			methods = {HttpMethod.GET},
			summary = "List saved multiplayer servers",
			description = "Returns the list of servers saved in the client's multiplayer server list.",
			tags = {"Server List"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Saved server list",
							content = {@OpenApiContent(from = ServersDto.ListResponse.class)}
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
			ServersDto.ListResponse response = gameClient.getServers();
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/servers",
			methods = {HttpMethod.POST},
			summary = "Add server to multiplayer server list",
			description = "Adds a new server entry to the client's saved server list.",
			tags = {"Server List"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = ServersDto.AddRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Server added",
							content = {@OpenApiContent(from = ServersDto.ListResponse.class)}
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
	public void add(Context ctx) {
		ServersDto.AddRequest request;
		try {
			request = ctx.bodyAsClass(ServersDto.AddRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for adding server");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			ServersDto.ListResponse response = gameClient.addServer(request);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/servers/{index}",
			methods = {HttpMethod.PATCH},
			summary = "Update saved server details",
			description = "Updates the name and/or address of a saved server at index.",
			tags = {"Server List"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "index", type = Integer.class, description = "Server list index", required = true)
			},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = ServersDto.UpdateRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Server updated",
							content = {@OpenApiContent(from = ServersDto.ListResponse.class)}
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
							status = "404",
							description = "Server not found",
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
		int index;
		try {
			index = Integer.parseInt(ctx.pathParam("index"));
		} catch (NumberFormatException e) {
			throw Problems.invalidParameter("index", "Server index must be an integer");
		}

		ServersDto.UpdateRequest request;
		try {
			request = ctx.bodyAsClass(ServersDto.UpdateRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for updating server");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			ServersDto.ListResponse response = gameClient.updateServer(index, request);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/servers/connect",
			methods = {HttpMethod.POST},
			summary = "Connect to a multiplayer server by address",
			description = "Connects the client to a remote multiplayer server specified by address (hostname or IP:port).",
			tags = {"Server List"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = ServersDto.ConnectRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Connection initiated",
							content = {@OpenApiContent(from = ServersDto.ConnectResponse.class)}
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
	public void connect(Context ctx) {
		ServersDto.ConnectRequest request;
		try {
			request = ctx.bodyAsClass(ServersDto.ConnectRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for server connect");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			ServersDto.ConnectResponse response = gameClient.connectServer(request.address());
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/servers/{index}",
			methods = {HttpMethod.DELETE},
			summary = "Request server deletion confirmation token",
			description = "Initiates two-step confirmation to remove a server from the multiplayer list. Returns a single-use token valid for 30 seconds.",
			tags = {"Server List"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "index", type = Integer.class, description = "Server list index", required = true)
			},
			responses = {
					@OpenApiResponse(
							status = "202",
							description = "Confirmation token generated",
							content = {@OpenApiContent(from = ServersDto.DeleteTokenResponse.class)}
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
		String indexStr = ctx.pathParam("index");
		String auth = ctx.header("Authorization");
		String bearer = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : "";

		ConfirmManager.TokenInfo tokenInfo = confirmManager.createToken(bearer, "delete_server:" + indexStr);
		ServersDto.DeleteTokenResponse response = new ServersDto.DeleteTokenResponse(tokenInfo.token(), tokenInfo.expiresAt());

		ctx.status(202);
		ctx.contentType("application/json");
		try {
			ctx.result(Json.MAPPER.writeValueAsString(response));
		} catch (Exception e) {
			throw Problems.internal("Failed to serialize server delete response: " + e.getMessage());
		}
	}

	@OpenApi(
			path = "/v1/servers/{index}/delete/confirm",
			methods = {HttpMethod.POST},
			summary = "Confirm server removal from list",
			description = "Removes the server entry from the multiplayer server list using the confirmation token from step 1.",
			tags = {"Server List"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "index", type = Integer.class, description = "Server list index", required = true)
			},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = ServersDto.DeleteConfirmRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Server removed",
							content = {@OpenApiContent(from = ServersDto.DeleteResponse.class)}
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
							description = "Server not found",
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
		int index;
		try {
			index = Integer.parseInt(ctx.pathParam("index"));
		} catch (NumberFormatException e) {
			throw Problems.invalidParameter("index", "Server index must be an integer");
		}

		ServersDto.DeleteConfirmRequest request;
		try {
			request = ctx.bodyAsClass(ServersDto.DeleteConfirmRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for server delete confirmation");
		}

		String auth = ctx.header("Authorization");
		String bearer = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : "";
		confirmManager.validateAndConsume(request.confirmToken(), bearer, "delete_server:" + index);

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			ServersDto.DeleteResponse response = gameClient.deleteServer(index);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
