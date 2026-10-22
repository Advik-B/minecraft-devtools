package dev.advik.mcdt.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.AccessFilter;
import dev.advik.mcdt.security.BearerToken;
import dev.advik.mcdt.world.stream.WorldStreamService;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import org.eclipse.jetty.websocket.api.Callback;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * WebSocket endpoint on {@code /v1/world/stream} for the 3D viewer. Frames are produced by {@link WorldStreamService} on
 * the client tick; this handler only authenticates, registers sessions and applies {@code view} messages:
 * {@code {"type":"view","cx":0,"cz":0,"radius":8,"follow":false,"entities":true}}.
 */
public final class WorldStreamWsHandler implements Consumer<WsConfig> {
	private static final String SESSION_ATTR = "mcdt.worldStream";

	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final WorldStreamService service;

	public WorldStreamWsHandler(McdtConfig config, BearerToken token, IntSupplier portSupplier, WorldStreamService service) {
		this.config = config;
		this.token = token;
		this.portSupplier = portSupplier;
		this.service = service;
	}

	@Override
	public void accept(WsConfig ws) {
		ws.onConnect(this::handleConnect);
		ws.onMessage(this::handleMessage);
		ws.onClose(ctx -> release(ctx));
		ws.onError(ctx -> release(ctx));
	}

	private void handleConnect(WsConnectContext ctx) {
		if (!validateHandshake(ctx)) {
			return;
		}
		int radius = WorldStreamService.DEFAULT_RADIUS;
		String radiusParam = ctx.queryParam("radius");
		if (radiusParam != null) {
			try {
				radius = Integer.parseInt(radiusParam.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		WorldStreamService.Session session = service.open(new WorldStreamService.Sink() {
			@Override
			public void send(ByteBuffer frame, Runnable done) {
				if (!ctx.session.isOpen()) {
					done.run();
					throw new IllegalStateException("session closed");
				}
				ctx.session.sendBinary(frame, Callback.from(done, error -> done.run()));
			}

			@Override
			public void close(int code, String reason) {
				ctx.closeSession(code, reason);
			}
		}, radius);
		ctx.attribute(SESSION_ATTR, session);
		ctx.enableAutomaticPings();
	}

	private void handleMessage(WsMessageContext ctx) {
		WorldStreamService.Session session = ctx.attribute(SESSION_ATTR);
		if (session == null) return;
		try {
			JsonNode node = Json.MAPPER.readTree(ctx.message());
			if (!"view".equals(node.path("type").asText())) return;
			session.setView(
					node.hasNonNull("cx") ? node.get("cx").asInt() : null,
					node.hasNonNull("cz") ? node.get("cz").asInt() : null,
					node.hasNonNull("radius") ? node.get("radius").asInt() : null,
					node.path("follow").asBoolean(false)
			);
			if (node.has("entities")) {
				session.setEntities(node.get("entities").asBoolean(true));
			}
		} catch (Exception ignored) {
			// Malformed view messages are ignored; the stream keeps its current view.
		}
	}

	private void release(WsContext ctx) {
		WorldStreamService.Session session = ctx.attribute(SESSION_ATTR);
		if (session != null) {
			service.close(session);
		}
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

		// Browsers cannot set headers on a WebSocket handshake, so the dashboard passes the token like /v1/events does.
		String auth = ctx.header("Authorization");
		String presented = null;
		if (auth != null && auth.startsWith("Bearer ")) {
			presented = auth.substring("Bearer ".length()).trim();
		} else if (ctx.queryParam("token") != null && !ctx.queryParam("token").isBlank()) {
			presented = ctx.queryParam("token").trim();
		}

		if (presented == null) {
			ctx.closeSession(1008, "Missing or invalid Bearer token");
			return false;
		}

		if (!token.matches(presented)) {
			ctx.closeSession(1008, "Invalid Bearer token");
			return false;
		}

		return true;
	}
}
