package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.api.v1.dto.InputMouseDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.AccessFilter;
import dev.advik.mcdt.security.BearerToken;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Dedicated WebSocket channel for realtime mouse input on {@code /v1/input/mouse}.
 * Streams continuous high-frequency mouse movements, buttons, and scrolling.
 */
public final class InputMouseWsHandler implements Consumer<WsConfig> {
	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public InputMouseWsHandler(
			McdtConfig config,
			BearerToken token,
			IntSupplier portSupplier,
			TaskQueue queue,
			GameClient gameClient
	) {
		this.config = config;
		this.token = token;
		this.portSupplier = portSupplier;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@Override
	public void accept(WsConfig ws) {
		ws.onConnect(this::handleConnect);
		ws.onMessage(this::handleMessage);
		ws.onClose(this::handleClose);
		ws.onError(this::handleError);
	}

	private void handleConnect(WsConnectContext ctx) {
		if (!validateHandshake(ctx)) {
			return;
		}

		try {
			String welcome = Json.MAPPER.writeValueAsString(Map.of(
					"channel", "mouse",
					"status", "connected"
			));
			ctx.send(welcome);
		} catch (Exception ignored) {
		}
	}

	private void handleMessage(WsMessageContext ctx) {
		InputMouseDto.Request req;
		try {
			req = Json.MAPPER.readValue(ctx.message(), InputMouseDto.Request.class);
		} catch (Exception e) {
			try {
				ctx.send(Json.MAPPER.writeValueAsString(Map.of(
						"status", "error",
						"error", "Invalid mouse frame JSON: " + e.getMessage()
				)));
			} catch (Exception ignored) {
			}
			return;
		}

		queue.submit(() -> Json.MAPPER.valueToTree(gameClient.mouseInput(req)), config.requestTimeoutMs);
	}

	private void handleClose(WsContext ctx) {
		Mcdt.LOGGER.debug("Mouse WebSocket channel closed for session {}", ctx.sessionId());
	}

	private void handleError(io.javalin.websocket.WsErrorContext ctx) {
		Mcdt.LOGGER.debug("Mouse WebSocket error for session {}: {}", ctx.sessionId(), ctx.error() != null ? ctx.error().getMessage() : "unknown");
	}

	private boolean validateHandshake(WsContext ctx) {
		if (!config.permission.grants(Permission.ACT)) {
			ctx.closeSession(1008, "Permission denied: ACT required for input channels");
			return false;
		}

		String hostHeader = ctx.header("Host");
		if (hostHeader == null || hostHeader.isBlank()) {
			ctx.closeSession(1008, "Missing Host header");
			return false;
		}
		if (!AccessFilter.isHostAllowed(config, hostHeader)) {
			ctx.closeSession(1008, "Host not allowed");
			return false;
		}

		String origin = ctx.header("Origin");
		if (origin != null && !origin.isBlank() && !AccessFilter.isOriginAllowed(config, portSupplier.getAsInt(), origin)) {
			ctx.closeSession(1008, "Origin not allowed");
			return false;
		}

		String auth = ctx.header("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			ctx.closeSession(1008, "Missing or invalid Bearer token");
			return false;
		}

		String presented = auth.substring("Bearer ".length()).trim();
		if (!token.matches(presented)) {
			ctx.closeSession(1008, "Invalid Bearer token");
			return false;
		}

		return true;
	}
}
