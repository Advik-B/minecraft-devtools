package dev.advik.mcdt.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Token-bucket rate limiter keyed by caller (the bearer token). */
public final class RateLimiter {
	private final int perSecond;
	private final LongSupplier nanoClock;
	private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	public RateLimiter(int perSecond) {
		this(perSecond, System::nanoTime);
	}

	RateLimiter(int perSecond, LongSupplier nanoClock) {
		this.perSecond = perSecond;
		this.nanoClock = nanoClock;
	}

	/** Takes one permit. Returns {@code true} if the request may proceed. */
	public boolean tryAcquire(String key) {
		return buckets.computeIfAbsent(key, k -> new Bucket(perSecond, nanoClock.getAsLong()))
				.tryAcquire(perSecond, nanoClock.getAsLong());
	}

	private static final class Bucket {
		private double tokens;
		private long lastRefill;

		Bucket(int capacity, long now) {
			this.tokens = capacity;
			this.lastRefill = now;
		}

		synchronized boolean tryAcquire(int perSecond, long now) {
			double elapsedSeconds = (now - lastRefill) / 1_000_000_000.0;
			if (elapsedSeconds > 0) {
				tokens = Math.min(perSecond, tokens + elapsedSeconds * perSecond);
				lastRefill = now;
			}
			if (tokens >= 1) {
				tokens -= 1;
				return true;
			}
			return false;
		}
	}
}
