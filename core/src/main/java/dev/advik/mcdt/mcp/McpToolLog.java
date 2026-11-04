package dev.advik.mcdt.mcp;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe bounded log of recent MCP tool invocations.
 */
public final class McpToolLog {
	public record Entry(
			Instant timestamp,
			String sessionId,
			String toolName,
			String argumentsSummary,
			long durationMs,
			boolean success,
			String error
	) {}

	private final int capacity;
	private final Deque<Entry> entries = new ArrayDeque<>();

	public McpToolLog(int capacity) {
		this.capacity = Math.max(10, capacity);
	}

	public synchronized void record(
			String sessionId,
			String toolName,
			String argumentsSummary,
			long durationMs,
			boolean success,
			String error
	) {
		if (entries.size() >= capacity) {
			entries.pollFirst();
		}
		entries.addLast(new Entry(
				Instant.now(),
				sessionId,
				toolName,
				argumentsSummary,
				durationMs,
				success,
				error
		));
	}

	public synchronized List<Entry> getRecent(int limit) {
		List<Entry> list = new ArrayList<>(entries);
		if (limit > 0 && list.size() > limit) {
			return list.subList(list.size() - limit, list.size());
		}
		return list;
	}

	public synchronized void clear() {
		entries.clear();
	}
}
