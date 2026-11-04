package dev.advik.mcdt.mcp;

import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.events.EventRecord;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages active MCP sessions and coordinates resource update notifications from the event stream.
 */
public final class McpSessionManager implements AutoCloseable {
	private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
	private final long throttleMs;
	private final Consumer<EventRecord> eventListener;

	public McpSessionManager(long throttleMs) {
		this.throttleMs = throttleMs;
		this.eventListener = this::onEvent;
		EventHub.getInstance().addListener(this.eventListener);
	}

	private void onEvent(EventRecord record) {
		if (sessions.isEmpty() || record == null) {
			return;
		}

		String topic = record.topic();
		switch (topic) {
			case "tick" -> {
				notifyResource("mcdt://player", throttleMs);
				notifyResource("mcdt://world", throttleMs);
				notifyResource("mcdt://inventory", throttleMs);
			}
			case "screen.open", "screen.close" -> notifyResource("mcdt://screen", 0);
			case "world.join", "world.leave" -> {
				notifyResource("mcdt://world", 0);
				notifyResource("mcdt://player", 0);
				notifyResource("mcdt://hud", 0);
			}
			case "chat" -> notifyResource("mcdt://hud", throttleMs);
			default -> {}
		}
	}

	public McpSession getOrCreate(String sessionId) {
		return sessions.computeIfAbsent(sessionId, McpSession::new);
	}

	public McpSession get(String sessionId) {
		return sessions.get(sessionId);
	}

	public void remove(String sessionId) {
		McpSession s = sessions.remove(sessionId);
		if (s != null) {
			s.detachSseClient();
		}
	}

	public Collection<McpSession> activeSessions() {
		return sessions.values();
	}

	public void notifyResource(String uri, long customThrottleMs) {
		for (McpSession session : sessions.values()) {
			session.tryNotifyResource(uri, customThrottleMs);
		}
	}

	@Override
	public void close() {
		EventHub.getInstance().removeListener(this.eventListener);
		sessions.clear();
	}
}
