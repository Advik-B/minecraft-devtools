package dev.advik.mcdt.events;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Preallocated, lock-free circular ring buffer for events.
 * Publishers claim a monotonic sequence number via CAS and populate their slot.
 * The consumer polls published sequences without locking.
 */
public final class EventRingBuffer {
	private final int capacity;
	private final int mask;
	private final EventRecord[] slots;
	private final AtomicLongArray publishedSequences;
	private final AtomicLong cursor = new AtomicLong(0);

	public EventRingBuffer(int requestedCapacity) {
		this.capacity = nextPowerOfTwo(Math.max(64, requestedCapacity));
		this.mask = this.capacity - 1;
		this.slots = new EventRecord[this.capacity];
		this.publishedSequences = new AtomicLongArray(this.capacity);

		for (int i = 0; i < this.capacity; i++) {
			this.slots[i] = new EventRecord();
			this.publishedSequences.set(i, -1);
		}
	}

	public int capacity() {
		return capacity;
	}

	public int mask() {
		return mask;
	}

	/**
	 * Publishes an event to the ring buffer. Safe to call concurrently from any thread.
	 * Returns the sequence number assigned to this event.
	 */
	public long publish(String topic, long tick, long timestamp, Object payload) {
		long seq = cursor.getAndIncrement();
		int index = (int) (seq & mask);

		EventRecord slot = slots[index];
		synchronized (slot) {
			slot.set(seq, topic, tick, timestamp, payload);
			publishedSequences.set(index, seq);
		}

		return seq;
	}

	/**
	 * Checks if the slot at the given sequence has been published.
	 */
	public boolean isPublished(long seq) {
		int index = (int) (seq & mask);
		return publishedSequences.get(index) == seq;
	}

	/**
	 * Retrieves the slot for the given sequence.
	 * Must only be called if {@link #isPublished(long)} returned true.
	 */
	public EventRecord get(long seq) {
		int index = (int) (seq & mask);
		return slots[index];
	}

	/**
	 * Returns the highest sequence allocated so far.
	 */
	public long cursor() {
		return cursor.get();
	}

	private static int nextPowerOfTwo(int value) {
		int n = value - 1;
		n |= n >>> 1;
		n |= n >>> 2;
		n |= n >>> 4;
		n |= n >>> 8;
		n |= n >>> 16;
		return (n < 0) ? 1 : (n >= 1 << 30) ? 1 << 30 : n + 1;
	}
}
