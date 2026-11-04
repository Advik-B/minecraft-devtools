package dev.advik.mcdt.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

	@Test
	void testPermitAcquisitionAndRefill() {
		AtomicLong currentTimeNanos = new AtomicLong(1_000_000_000L);
		RateLimiter limiter = new RateLimiter(2, currentTimeNanos::get);

		// Initial capacity is 2 tokens
		assertTrue(limiter.tryAcquire("key1"));
		assertTrue(limiter.tryAcquire("key1"));

		// 3rd request in same instant is rejected
		assertFalse(limiter.tryAcquire("key1"));

		// Advance 500ms -> refills 1 token (0.5s * 2 tokens/s = 1 token)
		currentTimeNanos.addAndGet(500_000_000L);
		assertTrue(limiter.tryAcquire("key1"));
		assertFalse(limiter.tryAcquire("key1"));

		// Advance 2 full seconds -> refills up to capacity (max 2)
		currentTimeNanos.addAndGet(2_000_000_000L);
		assertTrue(limiter.tryAcquire("key1"));
		assertTrue(limiter.tryAcquire("key1"));
		assertFalse(limiter.tryAcquire("key1"));
	}

	@Test
	void testDistinctKeysHaveIndependentBuckets() {
		AtomicLong currentTimeNanos = new AtomicLong(1_000_000_000L);
		RateLimiter limiter = new RateLimiter(1, currentTimeNanos::get);

		assertTrue(limiter.tryAcquire("clientA"));
		assertFalse(limiter.tryAcquire("clientA"));

		// clientB is unaffected
		assertTrue(limiter.tryAcquire("clientB"));
		assertFalse(limiter.tryAcquire("clientB"));
	}
}
