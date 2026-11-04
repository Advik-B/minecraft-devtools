package dev.advik.mcdt.events;

import dev.advik.mcdt.Mcdt;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Composition root and central facade for MCDT's event streaming subsystem.
 */
public final class EventHub implements AutoCloseable {
	private static volatile EventHub instance;
	private static volatile LongSupplier tickSource = () -> 0L;

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

	/** Sets where {@link #publish(String, Object)} reads the current game tick; the platform sets this at startup. */
	public static void setTickSource(LongSupplier source) {
		tickSource = source != null ? source : () -> 0L;
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
		return publish(topic, tickSource.getAsLong(), payload);
	}

	public EventSubscriber registerSubscriber(EventSink sink, int queueCapacity) {
		EventSubscriber sub = new EventSubscriber(sink, queueCapacity);
		subscribersBySession.put(sink.id(), sub);
		dispatcher.addSubscriber(sub);
		return sub;
	}

	public EventSubscriber getSubscriber(String sinkId) {
		return subscribersBySession.get(sinkId);
	}

	public void removeSubscriber(String sinkId) {
		EventSubscriber sub = subscribersBySession.remove(sinkId);
		if (sub != null) {
			dispatcher.removeSubscriber(sub);
		}
	}

	public int subscriberCount() {
		return subscribersBySession.size();
	}

	public void addListener(java.util.function.Consumer<EventRecord> listener) {
		dispatcher.addListener(listener);
	}

	public void removeListener(java.util.function.Consumer<EventRecord> listener) {
		dispatcher.removeListener(listener);
	}

	public long totalDroppedEvents() {
		return dispatcher.totalDroppedEvents();
	}

	public void closeAll(int statusCode, String reason) {
		for (EventSubscriber sub : subscribersBySession.values()) {
			try {
				sub.sink().close(statusCode, reason);
			} catch (Exception e) {
				Mcdt.LOGGER.debug("Error closing subscriber session {}: {}", sub.sink().id(), e.getMessage());
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
