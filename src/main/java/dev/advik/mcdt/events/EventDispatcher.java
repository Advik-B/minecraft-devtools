package dev.advik.mcdt.events;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background dispatcher thread consuming published events from {@link EventRingBuffer}
 * and fanning them out to active {@link EventSubscriber}s.
 */
public final class EventDispatcher implements AutoCloseable {
	private final EventRingBuffer ringBuffer;
	private final List<EventSubscriber> subscribers = new CopyOnWriteArrayList<>();
	private final List<java.util.function.Consumer<EventRecord>> listeners = new CopyOnWriteArrayList<>();
	private final AtomicLong totalDroppedEvents = new AtomicLong(0);
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final Thread dispatcherThread;

	public EventDispatcher(EventRingBuffer ringBuffer) {
		this.ringBuffer = ringBuffer;
		this.dispatcherThread = new Thread(this::runLoop, "MCDT-EventDispatcher");
		this.dispatcherThread.setDaemon(true);
		this.dispatcherThread.start();
	}

	public void addSubscriber(EventSubscriber subscriber) {
		subscribers.add(subscriber);
	}

	public void removeSubscriber(EventSubscriber subscriber) {
		subscribers.remove(subscriber);
		subscriber.close();
	}

	public void addListener(java.util.function.Consumer<EventRecord> listener) {
		listeners.add(listener);
	}

	public void removeListener(java.util.function.Consumer<EventRecord> listener) {
		listeners.remove(listener);
	}

	public List<EventSubscriber> subscribers() {
		return subscribers;
	}

	public long totalDroppedEvents() {
		return totalDroppedEvents.get();
	}

	private void runLoop() {
		long nextSequence = 0;

		while (running.get() && !Thread.currentThread().isInterrupted()) {
			boolean processedAny = false;

			while (ringBuffer.isPublished(nextSequence)) {
				processedAny = true;
				EventRecord record = ringBuffer.get(nextSequence);

				for (java.util.function.Consumer<EventRecord> listener : listeners) {
					try {
						listener.accept(record);
					} catch (Throwable t) {
						Mcdt.LOGGER.warn("Error in event listener: {}", t.getMessage());
					}
				}

				if (!subscribers.isEmpty()) {
					dispatchRecord(record);
				}

				nextSequence++;
			}

			if (!processedAny) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}

	private void dispatchRecord(EventRecord record) {
		String defaultJson = null;

		for (EventSubscriber sub : subscribers) {
			if (sub.isClosed()) {
				subscribers.remove(sub);
				continue;
			}

			if (sub.matches(record.topic(), record.payload())) {
				String jsonToSend;

				if (defaultJson == null) {
					defaultJson = serialize(record.topic(), record.tick(), record.timestamp(), record.payload());
				}
				jsonToSend = defaultJson;

				sub.offer(jsonToSend, record.tick(), record.timestamp(), totalDroppedEvents::incrementAndGet);
			}
		}
	}

	private String serialize(String topic, long tick, long timestamp, Object payload) {
		try {
			EventEnvelope env = new EventEnvelope(topic, tick, timestamp, payload);
			return Json.MAPPER.writeValueAsString(env);
		} catch (Exception e) {
			Mcdt.LOGGER.warn("Failed to serialize event {} : {}", topic, e.getMessage());
			return "{\"topic\":\"" + topic + "\",\"error\":\"serialization_failed\"}";
		}
	}

	@Override
	public void close() {
		if (running.compareAndSet(true, false)) {
			dispatcherThread.interrupt();
			for (EventSubscriber sub : subscribers) {
				sub.close();
			}
			subscribers.clear();
		}
	}
}
