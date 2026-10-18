package dev.advik.mcdt.exec;

import java.util.concurrent.atomic.AtomicLong;

/** Counts client ticks. Advanced on the client thread, read from any thread. */
public final class TickClock {
	private final AtomicLong tick = new AtomicLong();

	public long advance() {
		return tick.incrementAndGet();
	}

	public long current() {
		return tick.get();
	}
}
