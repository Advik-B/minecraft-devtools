package dev.advik.mcdt.http;

import dev.advik.mcdt.events.EventSink;
import io.javalin.websocket.WsContext;

/** Javalin WebSocket connection as an {@link EventSink}. */
public record WsEventSink(WsContext ctx) implements EventSink {
	@Override
	public String id() {
		return ctx.sessionId();
	}

	@Override
	public void send(String message) {
		ctx.send(message);
	}

	@Override
	public void close(int statusCode, String reason) {
		ctx.closeSession(statusCode, reason);
	}
}
