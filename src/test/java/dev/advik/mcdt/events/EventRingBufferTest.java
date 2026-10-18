package dev.advik.mcdt.events;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EventRingBufferTest {

	@Test
	void publishesAndRetrievesSlotsInOrder() {
		EventRingBuffer buffer = new EventRingBuffer(64);
		assertEquals(64, buffer.capacity());

		long seq0 = buffer.publish("tick", 10, 1000L, 10);
		long seq1 = buffer.publish("chat", 10, 1001L, "hello");

		assertEquals(0, seq0);
		assertEquals(1, seq1);
		assertTrue(buffer.isPublished(0));
		assertTrue(buffer.isPublished(1));
		assertFalse(buffer.isPublished(2));

		EventRecord slot0 = buffer.get(0);
		assertEquals("tick", slot0.topic());
		assertEquals(10, slot0.tick());

		EventRecord slot1 = buffer.get(1);
		assertEquals("chat", slot1.topic());
		assertEquals("hello", slot1.payload());
	}

	@Test
	void wrapsSequencesAroundCapacity() {
		EventRingBuffer buffer = new EventRingBuffer(64);
		for (int i = 0; i < 200; i++) {
			long seq = buffer.publish("tick", i, 1000L + i, i);
			assertEquals(i, seq);
			assertTrue(buffer.isPublished(seq));
			assertEquals(i, buffer.get(seq).payload());
		}
	}

	@Test
	void concurrentPublishingMaintainsMonotonicSequences() throws Exception {
		int threads = 4;
		int itemsPerThread = 500;
		EventRingBuffer buffer = new EventRingBuffer(256);

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch latch = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			final int threadId = t;
			executor.submit(() -> {
				try {
					for (int i = 0; i < itemsPerThread; i++) {
						buffer.publish("test", threadId, System.currentTimeMillis(), i);
					}
				} finally {
					latch.countDown();
				}
			});
		}

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		executor.shutdown();

		assertEquals(threads * itemsPerThread, buffer.cursor());
	}
}
