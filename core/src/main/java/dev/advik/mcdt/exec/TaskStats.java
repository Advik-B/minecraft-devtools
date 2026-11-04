package dev.advik.mcdt.exec;

import java.util.Arrays;

/** Rolling execution-time statistics over the most recent tasks. */
public final class TaskStats {
	private static final int WINDOW = 1024;

	private final long[] samples = new long[WINDOW];
	private int next;
	private int size;
	private long total;

	public synchronized void record(long nanos) {
		samples[next] = nanos;
		next = (next + 1) % WINDOW;
		size = Math.min(size + 1, WINDOW);
		total++;
	}

	public synchronized Snapshot snapshot() {
		if (size == 0) {
			return new Snapshot(total, 0, 0);
		}
		long[] copy = Arrays.copyOf(samples, size);
		Arrays.sort(copy);
		long sum = 0;
		for (long sample : copy) {
			sum += sample;
		}
		int p99Index = Math.min(size - 1, (int) Math.ceil(size * 0.99) - 1);
		return new Snapshot(total, sum / size / 1_000, copy[p99Index] / 1_000);
	}

	/**
	 * @param tasksRun  tasks executed since start
	 * @param avgMicros mean execution time over the window
	 * @param p99Micros 99th percentile execution time over the window
	 */
	public record Snapshot(long tasksRun, long avgMicros, long p99Micros) {
	}
}
