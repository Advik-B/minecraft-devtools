package dev.advik.mcdt.mcp;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.mcp.dto.JsonRpcMessage;
import io.javalin.http.sse.SseClient;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks an active MCP client session, subscribed resources, and SSE sink.
 */
public final class McpSession {
	private final String sessionId;
	private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
	private final Map<String, Long> lastNotificationTimes = new ConcurrentHashMap<>();
	private volatile SseClient sseClient;

	public McpSession(String sessionId) {
		this.sessionId = sessionId;
	}

	public String sessionId() {
		return sessionId;
	}

	public void attachSseClient(SseClient client) {
		this.sseClient = client;
	}

	public void detachSseClient() {
		this.sseClient = null;
	}

	public boolean hasActiveSse() {
		SseClient client = this.sseClient;
		return client != null && !client.terminated();
	}

	public void subscribe(String uri) {
		subscriptions.add(uri);
	}

	public void unsubscribe(String uri) {
		subscriptions.remove(uri);
		lastNotificationTimes.remove(uri);
	}

	public boolean isSubscribed(String uri) {
		return subscriptions.contains(uri);
	}

	public Set<String> subscriptions() {
		return Collections.unmodifiableSet(subscriptions);
	}

	public boolean tryNotifyResource(String uri, long throttleMs) {
		if (!isSubscribed(uri) || !hasActiveSse()) {
			return false;
		}
		long now = System.currentTimeMillis();
		Long last = lastNotificationTimes.get(uri);
		if (last != null && (now - last) < throttleMs) {
			return false;
		}
		lastNotificationTimes.put(uri, now);

		JsonRpcMessage.Notification notif = JsonRpcMessage.Notification.of(
				"notifications/resources/updated",
				Map.of("uri", uri)
		);

		try {
			String json = Json.MAPPER.writeValueAsString(notif);
			SseClient client = this.sseClient;
			if (client != null && !client.terminated()) {
				client.sendEvent("message", json);
				return true;
			}
		} catch (Exception e) {
			Mcdt.LOGGER.warn("Failed to send resource update notification to session {}: {}", sessionId, e.getMessage());
		}
		return false;
	}

	public void sendResponse(JsonRpcMessage.Response response) {
		SseClient client = this.sseClient;
		if (client != null && !client.terminated()) {
			try {
				String json = Json.MAPPER.writeValueAsString(response);
				client.sendEvent("message", json);
			} catch (Exception e) {
				Mcdt.LOGGER.warn("Failed to send SSE response to session {}: {}", sessionId, e.getMessage());
			}
		}
	}
}
