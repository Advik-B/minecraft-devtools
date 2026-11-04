package dev.advik.mcdt.exec;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.http.Problems;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bounded queue of work for the client thread. HTTP threads {@link #submit} tasks; the client tick hook calls
 * {@link #drain} with a time budget so a flood of requests can never stall a frame.
 */
public final class TaskQueue {
	private final ArrayBlockingQueue<GameTask> queue;
	private final int capacity;
	private final TickClock clock;
	private final TaskStats stats = new TaskStats();
	private volatile boolean closed;

	public TaskQueue(int capacity, TickClock clock) {
		this.queue = new ArrayBlockingQueue<>(capacity);
		this.capacity = capacity;
		this.clock = clock;
	}

	/**
	 * Queues work for the client thread.
	 *
	 * @return a future that completes on the client thread, or exceptionally with {@link TimeoutException} if the
	 * task did not run within {@code timeoutMs}
	 * @throws dev.advik.mcdt.http.ProblemException {@code queue-full} or {@code shutting-down}
	 */
	public CompletableFuture<TaskResult> submit(Supplier<JsonNode> work, long timeoutMs) {
		if (closed) {
			throw Problems.shuttingDown();
		}
		GameTask task = new GameTask(work, System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMs));
		if (!queue.offer(task)) {
			throw Problems.queueFull();
		}
		task.future().orTimeout(timeoutMs, TimeUnit.MILLISECONDS).whenComplete((result, error) -> {
			if (error instanceof TimeoutException) {
				task.cancel();
			}
		});
		return task.future();
	}

	/**
	 * Runs queued tasks until the budget is spent or the queue is empty. Must be called on the client thread.
	 *
	 * @return number of tasks executed
	 */
	public int drain(long budgetNanos) {
		long start = System.nanoTime();
		int ran = 0;
		GameTask task;
		while (System.nanoTime() - start < budgetNanos && (task = queue.poll()) != null) {
			long begin = System.nanoTime();
			if (task.expired(begin)) {
				task.cancel();
				task.future().completeExceptionally(new TimeoutException());
				continue;
			}
			if (!task.tryStart()) {
				continue;
			}
			try {
				JsonNode body = task.work().get();
				task.future().complete(new TaskResult(body, clock.current(), begin - task.enqueuedNanos()));
			} catch (VirtualMachineError fatal) {
				task.future().completeExceptionally(fatal);
				throw fatal;
			} catch (Throwable error) {
				task.future().completeExceptionally(error);
			} finally {
				stats.record(System.nanoTime() - begin);
				ran++;
			}
		}
		return ran;
	}

	/** Rejects new work and fails everything still queued. */
	public void close() {
		closed = true;
		GameTask task;
		int failed = 0;
		while ((task = queue.poll()) != null) {
			task.cancel();
			task.future().completeExceptionally(Problems.shuttingDown());
			failed++;
		}
		if (failed > 0) {
			Mcdt.LOGGER.debug("Failed {} queued MCDT tasks on shutdown", failed);
		}
	}

	public int depth() {
		return queue.size();
	}

	public int capacity() {
		return capacity;
	}

	public TaskStats stats() {
		return stats;
	}

	public TickClock clock() {
		return clock;
	}
}
