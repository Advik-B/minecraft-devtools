package dev.advik.mcdt.reflection;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Minecraft-specific conversions for the reflection engine: positions, ids, text, items, block states, entities.
 * Found with {@link java.util.ServiceLoader} through {@link GameTypeAdapters}; the platform jar registers one in
 * {@code META-INF/services}, so the engine itself has no Minecraft types.
 */
public interface GameTypeAdapter {
	/** Returned when an adapter does not handle a value or type. */
	Object UNHANDLED = new Object();

	/** A JSON-ready form of {@code value}, or {@link #UNHANDLED}. */
	Object serialize(Object value);

	/**
	 * A {@code targetType} value read from {@code node}, or {@link #UNHANDLED}.
	 * Throws a {@link dev.advik.mcdt.http.ProblemException} for malformed input of a handled type.
	 */
	Object convert(JsonNode node, Class<?> targetType);
}
