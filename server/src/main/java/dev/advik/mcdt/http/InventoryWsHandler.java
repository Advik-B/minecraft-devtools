package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.api.v1.dto.InventoryDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.AccessFilter;
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
 * Dedicated WebSocket channel for streaming inventory data on {@code /v1/player/inventory}.
 * Pushes a full inventory snapshot on connect and live slot diff updates when inventory changes.
 */
public final class InventoryWsHandler implements Consumer<WsConfig> {
	private static final List<WsContext> ACTIVE_SESSIONS = new CopyOnWriteArrayList<>();

	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public InventoryWsHandler(
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

	public static void broadcastSlotUpdate(int slot, Object item, long tick) {
		if (ACTIVE_SESSIONS.isEmpty()) return;
		try {
			String json = Json.MAPPER.writeValueAsString(Map.of(
					"type", "slot_update",
					"tick", tick,
					"slot", slot,
					"item", item != null ? item : Map.of("empty", true)
			));
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

		// Send initial snapshot via TaskQueue
		queue.submit(() -> Json.MAPPER.valueToTree(gameClient.getInventory()), config.requestTimeoutMs)
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
		// Client requested refresh
		queue.submit(() -> Json.MAPPER.valueToTree(gameClient.getInventory()), config.requestTimeoutMs)
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
