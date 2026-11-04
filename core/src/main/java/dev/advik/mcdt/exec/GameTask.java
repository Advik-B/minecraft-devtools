package dev.advik.mcdt.exec;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A unit of work for the client thread. The work closure must return fully serialized JSON so no game object
 * escapes the client thread.
 */
final class GameTask {
	private static final int PENDING = 0;
	private static final int RUNNING = 1;
	private static final int CANCELLED = 2;

	private final Supplier<JsonNode> work;
	private final long enqueuedNanos;
	private final long deadlineNanos;
	private final CompletableFuture<TaskResult> future = new CompletableFuture<>();
	private final AtomicInteger state = new AtomicInteger(PENDING);

	GameTask(Supplier<JsonNode> work, long enqueuedNanos, long timeoutNanos) {
		this.work = work;
		this.enqueuedNanos = enqueuedNanos;
		this.deadlineNanos = enqueuedNanos + timeoutNanos;
	}

	Supplier<JsonNode> work() {
		return work;
	}

	long enqueuedNanos() {
		return enqueuedNanos;
	}

	CompletableFuture<TaskResult> future() {
		return future;
	}

	boolean expired(long now) {
		return now - deadlineNanos > 0;
	}

	/** Claims the task for execution. Fails if it was cancelled or already completed (e.g. timed out). */
	boolean tryStart() {
		return !future.isDone() && state.compareAndSet(PENDING, RUNNING);
	}

	/** Cancels the task if it has not started. Returns {@code true} if it will never run. */
	boolean cancel() {
		return state.compareAndSet(PENDING, CANCELLED) || state.get() == CANCELLED;
	}
}
