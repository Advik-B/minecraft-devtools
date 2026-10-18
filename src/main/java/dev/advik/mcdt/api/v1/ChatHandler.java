package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ChatDto;
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
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

import java.util.concurrent.CompletableFuture;

public final class ChatHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public ChatHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/chat",
			methods = {HttpMethod.POST},
			summary = "Send chat message or command",
			description = "Sends a chat message to the server or executes an in-game command (prefixed with '/' or in command field).",
			tags = {"Chat"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = ChatDto.SendRequest.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Message or command sent", content = {@OpenApiContent(from = ChatDto.SendResponse.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "409", description = "No player loaded", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@OpenApi(
			path = "/v1/chat",
			methods = {HttpMethod.GET},
			summary = "Read chat history",
			description = "Returns paginated recent in-game chat messages.",
			tags = {"Chat"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "offset", description = "Pagination offset", type = Integer.class),
					@OpenApiParam(name = "limit", description = "Number of messages (default 50)", type = Integer.class)
			},
			responses = {
					@OpenApiResponse(status = "200", description = "Chat history", content = {@OpenApiContent(from = ChatDto.HistoryResponse.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		long timeoutMs = config.requestTimeoutMs;

		if (ctx.method() == HandlerType.POST) {
			ChatDto.SendRequest request;
			try {
				request = ctx.bodyAsClass(ChatDto.SendRequest.class);
			} catch (Exception e) {
				throw Problems.invalidParameter("body", "Invalid request body: " + e.getMessage());
			}

			CompletableFuture<Void> future = queue.submit(() -> {
				ChatDto.SendResponse response = gameClient.sendChat(request);
				return Json.MAPPER.valueToTree(response);
			}, timeoutMs).thenAccept(result -> {
				ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
				ctx.contentType("application/json");
				ctx.result(result.body().toString());
			});
			ctx.future(() -> future);
		} else {
			int offset = 0;
			int limit = 50;
			try {
				if (ctx.queryParam("offset") != null) {
					offset = Integer.parseInt(ctx.queryParam("offset"));
				}
				if (ctx.queryParam("limit") != null) {
					limit = Integer.parseInt(ctx.queryParam("limit"));
				}
			} catch (NumberFormatException e) {
				throw Problems.invalidParameter("offset/limit", "Query parameters offset and limit must be integers");
			}

			int finalOffset = Math.max(0, offset);
			int finalLimit = Math.max(1, Math.min(limit, 200));

			CompletableFuture<Void> future = queue.submit(() -> {
				ChatDto.HistoryResponse response = gameClient.getChatHistory(finalOffset, finalLimit);
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
