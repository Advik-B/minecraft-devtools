package dev.advik.mcdt.exec;

import com.fasterxml.jackson.databind.node.IntNode;
import dev.advik.mcdt.http.ProblemException;
import dev.advik.mcdt.http.Problems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class TaskQueueTest {
	private TickClock clock;
	private TaskQueue queue;

	@BeforeEach
	void setUp() {
		clock = new TickClock();
		queue = new TaskQueue(3, clock);
	}

	@Test
	void executesTaskAndReturnsResultWithTick() throws Exception {
		clock.advance(); // tick 1
		CompletableFuture<TaskResult> future = queue.submit(() -> new IntNode(42), 1000);

		assertEquals(1, queue.depth());
		assertFalse(future.isDone());

		int executed = queue.drain(TimeUnit.MILLISECONDS.toNanos(10));
		assertEquals(1, executed);
		assertEquals(0, queue.depth());
		assertTrue(future.isDone());

		TaskResult result = future.get(1, TimeUnit.SECONDS);
		assertEquals(42, result.body().asInt());
		assertEquals(1, result.tick());
	}

	@Test
	void throwsQueueFullWhenCapacityExceeded() {
		queue.submit(() -> new IntNode(1), 1000);
		queue.submit(() -> new IntNode(2), 1000);
		queue.submit(() -> new IntNode(3), 1000);

		ProblemException ex = assertThrows(ProblemException.class, () ->
				queue.submit(() -> new IntNode(4), 1000)
		);
		assertEquals(Problems.QUEUE_FULL, ex.problem().type());
		assertEquals(503, ex.problem().status());
	}

	@Test
	void respectsBudgetAndLeavesRemainingTasksForNextDrain() {
		queue.submit(() -> {
			sleep(5);
			return new IntNode(1);
		}, 1000);
		queue.submit(() -> {
			sleep(5);
			return new IntNode(2);
		}, 1000);

		// Budget is 3ms -> only first task should run
		int ran = queue.drain(TimeUnit.MILLISECONDS.toNanos(3));
		assertEquals(1, ran);
		assertEquals(1, queue.depth());

		// Second drain completes the second task
		ran = queue.drain(TimeUnit.MILLISECONDS.toNanos(10));
		assertEquals(1, ran);
		assertEquals(0, queue.depth());
	}

	@Test
	void taskFailureCompletesFutureExceptionallyWithoutStoppingDrain() {
		CompletableFuture<TaskResult> failingFuture = queue.submit(() -> {
			throw new RuntimeException("Simulated task explosion");
		}, 1000);
		CompletableFuture<TaskResult> okFuture = queue.submit(() -> new IntNode(99), 1000);

		queue.drain(TimeUnit.MILLISECONDS.toNanos(10));

		ExecutionException ex = assertThrows(ExecutionException.class, failingFuture::get);
		assertInstanceOf(RuntimeException.class, ex.getCause());
		assertEquals("Simulated task explosion", ex.getCause().getMessage());

		assertDoesNotThrow(() -> assertEquals(99, okFuture.get().body().asInt()));
	}

	@Test
	void recordsRollingStatistics() {
		for (int i = 0; i < 5; i++) {
			queue.submit(() -> new IntNode(1), 1000);
			queue.drain(TimeUnit.MILLISECONDS.toNanos(10));
		}

		TaskStats.Snapshot snap = queue.stats().snapshot();
		assertEquals(5, snap.tasksRun());
		assertTrue(snap.avgMicros() >= 0);
	}

	@Test
	void closedQueueRejectsAndFailsPending() {
		CompletableFuture<TaskResult> future = queue.submit(() -> new IntNode(1), 1000);
		queue.close();

		assertThrows(ProblemException.class, () -> queue.submit(() -> new IntNode(2), 1000));
		ExecutionException ex = assertThrows(ExecutionException.class, future::get);
		assertInstanceOf(ProblemException.class, ex.getCause());
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
