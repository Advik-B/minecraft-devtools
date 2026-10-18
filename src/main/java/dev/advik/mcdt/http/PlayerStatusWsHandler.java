package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.api.v1.dto.PlayerDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.BearerToken;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Dedicated WebSocket channel for streaming player status and sudden damage/death events on {@code /v1/player/status}.
 */
public final class PlayerStatusWsHandler implements Consumer<WsConfig> {
	private static final List<WsContext> ACTIVE_SESSIONS = new CopyOnWriteArrayList<>();

	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public PlayerStatusWsHandler(
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

	public static void broadcastDamage(float amount, float health, String source, long tick) {
		broadcast(Map.of(
				"type", "damage",
				"tick", tick,
				"amount", amount,
				"health", health,
				"source", source != null ? source : "generic"
		));
	}

	public static void broadcastDeath(String message, long tick) {
		broadcast(Map.of(
				"type", "death",
				"tick", tick,
				"message", message != null ? message : "Player died"
		));
	}

	public static void broadcastRespawn(float health, long tick) {
		broadcast(Map.of(
				"type", "respawn",
				"tick", tick,
				"health", health
		));
	}

	private static void broadcast(Map<String, Object> payload) {
		if (ACTIVE_SESSIONS.isEmpty()) return;
		try {
			String json = Json.MAPPER.writeValueAsString(payload);
			for (WsContext ctx : ACTIVE_SESSIONS) {
				try {
					ctx.send(json);
				} catch (Exception e) {
					ACTIVE_SESSIONS.remove(ctx);
				}
			}
		} catch (Exception ignored) {
		}
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

		ACTIVE_SESSIONS.add(ctx);

		queue.submit(() -> Json.MAPPER.valueToTree(gameClient.getPlayer()), config.requestTimeoutMs)
				.thenAccept(res -> {
					try {
						String snapshot = Json.MAPPER.writeValueAsString(Map.of(
								"type", "snapshot",
								"tick", res.tick(),
								"data", res.body()
						));
						ctx.send(snapshot);
					} catch (Exception ignored) {
					}
				});
	}

	private void handleMessage(WsMessageContext ctx) {
		queue.submit(() -> Json.MAPPER.valueToTree(gameClient.getPlayer()), config.requestTimeoutMs)
				.thenAccept(res -> {
					try {
						String snapshot = Json.MAPPER.writeValueAsString(Map.of(
								"type", "snapshot",
								"tick", res.tick(),
								"data", res.body()
						));
						ctx.send(snapshot);
					} catch (Exception ignored) {
					}
				});
	}

	private void handleClose(WsContext ctx) {
		ACTIVE_SESSIONS.remove(ctx);
	}

	private void handleError(io.javalin.websocket.WsErrorContext ctx) {
		ACTIVE_SESSIONS.remove(ctx);
	}

	private boolean validateHandshake(WsContext ctx) {
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
