package dev.advik.mcdt.events;

/**
 * A mutable event slot in the preallocated {@link EventRingBuffer}.
 * Using mutable reusable slots avoids garbage allocation on the game and network threads.
 */
public final class EventRecord {
	private long sequence = -1;
	private String topic;
	private long tick;
	private long timestamp;
	private Object payload;
	private volatile String cachedJson;

	public void set(long sequence, String topic, long tick, long timestamp, Object payload) {
		this.sequence = sequence;
		this.topic = topic;
		this.tick = tick;
		this.timestamp = timestamp;
		this.payload = payload;
		this.cachedJson = null;
	}

	public long sequence() {
		return sequence;
	}

	public String topic() {
		return topic;
	}

	public long tick() {
		return tick;
	}

	public long timestamp() {
		return timestamp;
	}

	public Object payload() {
		return payload;
	}

	public String cachedJson() {
		return cachedJson;
	}

	public void setCachedJson(String json) {
		this.cachedJson = json;
	}

	public void clear() {
		this.sequence = -1;
		this.topic = null;
		this.tick = 0;
		this.timestamp = 0;
		this.payload = null;
		this.cachedJson = null;
	}
}
