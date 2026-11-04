package dev.advik.mcdt.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.Mcdt;

import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** The {@link GameTypeAdapter}s registered by the platform, tried in order. */
public final class GameTypeAdapters {
	private static final List<GameTypeAdapter> ADAPTERS = load();

	private GameTypeAdapters() {
	}

	private static List<GameTypeAdapter> load() {
		try {
			return ServiceLoader.load(GameTypeAdapter.class, GameTypeAdapters.class.getClassLoader())
					.stream().map(ServiceLoader.Provider::get).toList();
		} catch (ServiceConfigurationError | LinkageError e) {
			Mcdt.LOGGER.warn("Could not load the platform game type adapters: {}", e.getMessage());
			return List.of();
		}
	}

	/** See {@link GameTypeAdapter#serialize(Object)}. */
	public static Object serialize(Object value) {
		for (GameTypeAdapter adapter : ADAPTERS) {
			Object result = adapter.serialize(value);
			if (result != GameTypeAdapter.UNHANDLED) {
				return result;
			}
		}
		return GameTypeAdapter.UNHANDLED;
	}

	/** See {@link GameTypeAdapter#convert(JsonNode, Class)}. */
	public static Object convert(JsonNode node, Class<?> targetType) {
		for (GameTypeAdapter adapter : ADAPTERS) {
			Object result = adapter.convert(node, targetType);
			if (result != GameTypeAdapter.UNHANDLED) {
				return result;
			}
		}
		return GameTypeAdapter.UNHANDLED;
	}
}
