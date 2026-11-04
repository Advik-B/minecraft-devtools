package dev.advik.mcdt.events;

public record EventEnvelope(
		String topic,
		long tick,
		long timestamp,
		Object data
) {
}
