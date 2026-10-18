package dev.advik.mcdt.events;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;
import io.javalin.websocket.WsContext;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages an active WebSocket subscriber connection with an isolated, bounded outgoing queue.
 * Drops events if the subscriber is too slow and generates gap notifications.
 */
public final class EventSubscriber {
	private final WsContext ctx;
	private final BlockingQueue<String> outgoingQueue;
	private final Set<String> subscribedTopics = ConcurrentHashMap.newKeySet();
	private final Map<String, SubscriptionRequest.TopicFilter> filters = new ConcurrentHashMap<>();
	private final AtomicLong droppedCount = new AtomicLong(0);
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final Thread senderThread;

	public EventSubscriber(WsContext ctx, int queueCapacity) {
		this.ctx = ctx;
		this.outgoingQueue = new ArrayBlockingQueue<>(Math.max(32, queueCapacity));
		this.senderThread = new Thread(this::runSender, "MCDT-EventSubscriber-" + ctx.sessionId());
		this.senderThread.setDaemon(true);
		this.senderThread.start();
	}

	public WsContext context() {
		return ctx;
	}

	public boolean isClosed() {
		return closed.get();
	}

	public void updateSubscription(SubscriptionRequest req) {
		if (req == null) return;
		boolean isUnsubscribe = "unsubscribe".equalsIgnoreCase(req.action);

		if (req.topics != null) {
			for (String t : req.topics) {
				if (t == null || t.isBlank()) continue;
				String topic = t.trim().toLowerCase();
				if (isUnsubscribe) {
					if ("*".equals(topic)) {
						subscribedTopics.clear();
					} else {
						subscribedTopics.remove(topic);
					}
				} else {
					subscribedTopics.add(topic);
				}
			}
		}

		if (!isUnsubscribe && req.filters != null) {
			req.filters.forEach((k, v) -> {
				if (k != null && v != null) {
					filters.put(k.trim().toLowerCase(), v);
				}
			});
		}
	}

	public Set<String> subscribedTopics() {
		return Collections.unmodifiableSet(subscribedTopics);
	}

	public boolean matches(String topic, Object payload) {
		if (closed.get()) return false;
		if (!subscribedTopics.contains("*") && !subscribedTopics.contains(topic.toLowerCase())) {
			return false;
		}

		if ("packet.in".equalsIgnoreCase(topic) && payload instanceof String packetName) {
			SubscriptionRequest.TopicFilter filter = filters.get("packet.in");
			if (filter != null && filter.types != null && !filter.types.isEmpty()) {
				boolean matched = false;
				for (String allowed : filter.types) {
					if (packetName.equalsIgnoreCase(allowed) || packetName.endsWith("." + allowed)) {
						matched = true;
						break;
					}
				}
				if (!matched) return false;
			}
		}

		return true;
	}

	public boolean wantsFullPayload(String topic) {
		SubscriptionRequest.TopicFilter filter = filters.get(topic.toLowerCase());
		return filter != null && filter.fullPayload;
	}

	public void offer(String json, long tick, long timestamp, Runnable onDropCallback) {
		if (closed.get()) return;

		long dropped = droppedCount.get();
		if (dropped > 0) {
			// Try to insert a gap message
			String gapJson = formatGapMessage(dropped, tick, timestamp);
			if (outgoingQueue.offer(gapJson)) {
				droppedCount.set(0);
			}
		}

		if (!outgoingQueue.offer(json)) {
			droppedCount.incrementAndGet();
			if (onDropCallback != null) {
				onDropCallback.run();
			}
		}
	}

	private String formatGapMessage(long dropped, long tick, long timestamp) {
		try {
			EventEnvelope env = new EventEnvelope(
					"gap",
					tick,
					timestamp,
					Map.of("dropped", dropped, "reason", "buffer_overflow")
			);
			return Json.MAPPER.writeValueAsString(env);
		} catch (Exception e) {
			return "{\"topic\":\"gap\",\"data\":{\"dropped\":" + dropped + "}}";
		}
	}

	private void runSender() {
		while (!closed.get() && !Thread.currentThread().isInterrupted()) {
			try {
				String msg = outgoingQueue.take();
				try {
					ctx.send(msg);
				} catch (Exception e) {
					close();
					break;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				Mcdt.LOGGER.debug("Error sending event to subscriber {}: {}", ctx.sessionId(), e.getMessage());
				close();
				break;
			}
		}
	}

	public void close() {
		if (closed.compareAndSet(false, true)) {
			outgoingQueue.clear();
			senderThread.interrupt();
		}
	}
}
