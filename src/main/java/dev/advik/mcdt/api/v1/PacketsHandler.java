package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.PacketDto.*;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.network.PacketResolver;
import dev.advik.mcdt.reflection.HandleTable;
import dev.advik.mcdt.reflection.NameService;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

public final class PacketsHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final HandleTable handleTable;

	public PacketsHandler(McdtConfig config, TaskQueue queue, GameClient gameClient, HandleTable handleTable) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
		this.handleTable = handleTable;
	}

	@OpenApi(
			path = "/v1/packets",
			methods = {HttpMethod.POST},
			summary = "Send a raw serverbound packet",
			description = "Builds any serverbound packet from JSON and transmits it through the live network connection. Requires reflect-write permission.",
			tags = {"Packets"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = SendRequest.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Packet sent successfully", content = {@OpenApiContent(from = SendResponse.class)}),
					@OpenApiResponse(status = "400", description = "Invalid packet parameters or construction failed", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied or multiplayer writes disabled", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "404", description = "Packet class not found", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "409", description = "No world or active connection", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		SendRequest request;
		try {
			request = ctx.bodyAsClass(SendRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Invalid request body: " + e.getMessage());
		}

		if (request.packet() == null || request.packet().isBlank()) {
			throw Problems.badRequest("Missing required 'packet' name");
		}

		Class<?> packetClass = PacketResolver.resolvePacketClass(request.packet());
		Object packet = PacketResolver.constructPacket(packetClass, request.data(), handleTable);

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			gameClient.sendPacket(packet);
			return Json.MAPPER.valueToTree(new SendResponse(true, NameService.getInstance().resolveClassName(packetClass.getName()), 0));
		}, timeoutMs).thenAccept(res -> {
			SendResponse resp = new SendResponse(true, NameService.getInstance().resolveClassName(packetClass.getName()), res.tick());
			ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
			ctx.contentType("application/json");
			ctx.result(Json.MAPPER.valueToTree(resp).toString());
		});

		ctx.future(() -> future);
	}
}
