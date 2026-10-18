package dev.advik.mcdt.events;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.client.MinecraftDevToolsClient;
import io.javalin.websocket.WsContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Composition root and central facade for MCDT's event streaming subsystem.
 */
public final class EventHub implements AutoCloseable {
	private static volatile EventHub instance;

	private final EventRingBuffer ringBuffer;
	private final EventDispatcher dispatcher;
	private final Map<String, EventSubscriber> subscribersBySession = new ConcurrentHashMap<>();

	public static EventHub getInstance() {
		EventHub local = instance;
		if (local == null) {
			synchronized (EventHub.class) {
				local = instance;
				if (local == null) {
					local = new EventHub(4096);
					instance = local;
				}
			}
		}
		return local;
	}

	public static void init(int ringBufferSize) {
		synchronized (EventHub.class) {
			if (instance != null) {
				instance.close();
			}
			instance = new EventHub(ringBufferSize);
		}
	}

	public static void resetForTesting() {
		synchronized (EventHub.class) {
			if (instance != null) {
				instance.close();
				instance = null;
			}
		}
	}

	public EventHub(int ringBufferSize) {
		this.ringBuffer = new EventRingBuffer(ringBufferSize);
		this.dispatcher = new EventDispatcher(this.ringBuffer);
	}

	public EventRingBuffer ringBuffer() {
		return ringBuffer;
	}

	public EventDispatcher dispatcher() {
		return dispatcher;
	}

	/**
	 * Publishes an event into the ring buffer.
	 */
	public long publish(String topic, long tick, Object payload) {
		long now = System.currentTimeMillis();
		return ringBuffer.publish(topic, tick, now, payload);
	}

	/**
	 * Publishes an event with current game tick resolved automatically.
	 */
	public long publish(String topic, Object payload) {
		long tick = MinecraftDevToolsClient.core()
				.map(c -> c.queue().clock().current())
				.orElse(0L);
		return publish(topic, tick, payload);
	}

	public EventSubscriber registerSubscriber(WsContext ctx, int queueCapacity) {
		EventSubscriber sub = new EventSubscriber(ctx, queueCapacity);
		subscribersBySession.put(ctx.sessionId(), sub);
		dispatcher.addSubscriber(sub);
		return sub;
	}

	public EventSubscriber getSubscriber(WsContext ctx) {
		return subscribersBySession.get(ctx.sessionId());
	}

	public void removeSubscriber(WsContext ctx) {
		EventSubscriber sub = subscribersBySession.remove(ctx.sessionId());
		if (sub != null) {
			dispatcher.removeSubscriber(sub);
		}
	}

	public int subscriberCount() {
		return subscribersBySession.size();
	}

	public long totalDroppedEvents() {
		return dispatcher.totalDroppedEvents();
	}

	public void closeAll(int statusCode, String reason) {
		for (EventSubscriber sub : subscribersBySession.values()) {
			try {
				sub.context().closeSession(statusCode, reason);
			} catch (Exception e) {
				Mcdt.LOGGER.debug("Error closing subscriber session {}: {}", sub.context().sessionId(), e.getMessage());
			}
			sub.close();
		}
		subscribersBySession.clear();
	}

	@Override
	public void close() {
		closeAll(1000, "Server stopping");
		dispatcher.close();
	}
}
