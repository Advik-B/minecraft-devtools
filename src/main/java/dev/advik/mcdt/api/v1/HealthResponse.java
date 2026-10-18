package dev.advik.mcdt.api.v1;

/**
 * Health and diagnostics response returned by {@code GET /v1/health}.
 */
public record HealthResponse(
		String mcdtVersion,
		String gameVersion,
		long uptimeMs,
		long tick,
		QueueStats queue,
		String baritone,
		String permission
) {
	public record QueueStats(
			int depth,
			int capacity,
			long avgTaskMicros,
			long p99TaskMicros
	) {
	}
}
