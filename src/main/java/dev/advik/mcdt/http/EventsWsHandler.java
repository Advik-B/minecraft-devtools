package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.events.EventSubscriber;
import dev.advik.mcdt.events.SubscriptionRequest;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.BearerToken;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * WebSocket handler for {@code GET /v1/events}.
 * Enforces Bearer authentication, Host validation, and Origin allowlist,
 * then streams real-time JSON events to subscribed clients.
 */
public final class EventsWsHandler implements Consumer<WsConfig> {
	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final EventHub eventHub;

	public EventsWsHandler(McdtConfig config, BearerToken token, IntSupplier portSupplier, EventHub eventHub) {
		this.config = config;
		this.token = token;
		this.portSupplier = portSupplier;
		this.eventHub = eventHub;
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

		EventSubscriber sub = eventHub.registerSubscriber(ctx, config.eventSubscriberQueueCapacity);

		try {
			String welcome = Json.MAPPER.writeValueAsString(Map.of(
					"topic", "subscription",
					"data", Map.of(
							"status", "connected",
							"supportedTopics", List.of(
									"tick", "chat", "screen.open", "screen.close",
									"world.join", "world.leave", "player.damage",
									"player.death", "entity.spawn", "entity.remove",
									"block.update", "packet.in"
							)
					)
			));
			ctx.send(welcome);
		} catch (Exception e) {
			Mcdt.LOGGER.warn("Failed to send welcome message: {}", e.getMessage());
		}
	}

	private void handleMessage(WsMessageContext ctx) {
		EventSubscriber sub = eventHub.getSubscriber(ctx);
		if (sub == null) {
			ctx.closeSession(1008, "Subscriber not registered");
			return;
		}

		try {
			SubscriptionRequest req = Json.MAPPER.readValue(ctx.message(), SubscriptionRequest.class);
			sub.updateSubscription(req);

			String ack = Json.MAPPER.writeValueAsString(Map.of(
					"topic", "subscription",
					"data", Map.of(
							"status", "active",
							"action", req.action != null ? req.action : "subscribe",
							"subscribedTopics", sub.subscribedTopics()
					)
			));
			ctx.send(ack);
		} catch (Exception e) {
			try {
				String error = Json.MAPPER.writeValueAsString(Map.of(
						"topic", "error",
						"data", Map.of("message", "Invalid subscription JSON: " + e.getMessage())
				));
				ctx.send(error);
			} catch (Exception ignored) {
			}
		}
	}

	private void handleClose(WsContext ctx) {
		eventHub.removeSubscriber(ctx);
	}

	private void handleError(io.javalin.websocket.WsErrorContext ctx) {
		Mcdt.LOGGER.debug("WebSocket error on session {}: {}", ctx.sessionId(), ctx.error() != null ? ctx.error().getMessage() : "unknown");
		eventHub.removeSubscriber(ctx);
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
