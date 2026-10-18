package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.api.v1.dto.InputKeyDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.BearerToken;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Dedicated WebSocket channel for realtime keyboard input on {@code /v1/input/keys}.
 * Streams key presses, releases, taps, and typing with zero HTTP overhead.
 */
public final class InputKeysWsHandler implements Consumer<WsConfig> {
	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public InputKeysWsHandler(
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
					"channel", "keys",
					"status", "connected"
			));
			ctx.send(welcome);
		} catch (Exception ignored) {
		}
	}

	private void handleMessage(WsMessageContext ctx) {
		InputKeyDto.Request req;
		try {
			req = Json.MAPPER.readValue(ctx.message(), InputKeyDto.Request.class);
		} catch (Exception e) {
			try {
				ctx.send(Json.MAPPER.writeValueAsString(Map.of(
						"status", "error",
						"error", "Invalid key frame JSON: " + e.getMessage()
				)));
			} catch (Exception ignored) {
			}
			return;
		}

		queue.submit(() -> Json.MAPPER.valueToTree(gameClient.pressKey(req)), config.requestTimeoutMs)
				.thenAccept(res -> {
					try {
						ctx.send(Json.MAPPER.writeValueAsString(Map.of(
								"status", "ok",
								"action", req.action() != null ? req.action() : "press",
								"key", req.key() != null ? req.key() : "",
								"tick", res.tick()
						)));
					} catch (Exception ignored) {
					}
				});
	}

	private void handleClose(WsContext ctx) {
		Mcdt.LOGGER.debug("Keyboard WebSocket channel closed for session {}", ctx.sessionId());
	}

	private void handleError(io.javalin.websocket.WsErrorContext ctx) {
		Mcdt.LOGGER.debug("Keyboard WebSocket error for session {}: {}", ctx.sessionId(), ctx.error() != null ? ctx.error().getMessage() : "unknown");
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

		String hostName = hostHeader.trim();
		if (hostName.startsWith("[")) {
			int closeBracket = hostName.indexOf(']');
			if (closeBracket != -1) {
				hostName = hostName.substring(1, closeBracket);
			}
		} else {
			int colon = hostName.indexOf(':');
			if (colon != -1) {
				hostName = hostName.substring(0, colon);
			}
		}

		boolean isLoopback = "127.0.0.1".equalsIgnoreCase(hostName) || "localhost".equalsIgnoreCase(hostName)
				|| "::1".equalsIgnoreCase(hostName);
		boolean matchesBind = config.bindAddress != null && config.bindAddress.equalsIgnoreCase(hostName);

		if (!isLoopback && !matchesBind) {
			ctx.closeSession(1008, "Host not allowed");
			return false;
		}

		String origin = ctx.header("Origin");
		if (origin != null && !origin.isBlank()) {
			int port = portSupplier.getAsInt();
			boolean isSelf = origin.equalsIgnoreCase("http://127.0.0.1:" + port)
					|| origin.equalsIgnoreCase("http://localhost:" + port);
			boolean isAllowed = isSelf || config.originAllowlist.stream().anyMatch(origin::equalsIgnoreCase);

			if (!isAllowed) {
				ctx.closeSession(1008, "Origin not allowed");
				return false;
			}
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
