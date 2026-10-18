package dev.advik.mcdt.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Permission levels, ordered from least to most powerful. A configured level grants every level below it.
 */
public enum Permission {
	/** Read game state. The default. */
	READ("read"),
	/** Input, chat, options, screen actions. */
	ACT("act"),
	/** Field set, method invoke, raw packet send. */
	REFLECT_WRITE("reflect-write");

	private final String id;

	Permission(String id) {
		this.id = id;
	}

	@JsonValue
	public String id() {
		return id;
	}

	/** Whether a client configured with this level may use a route that needs {@code required}. */
	public boolean grants(Permission required) {
		return this.ordinal() >= required.ordinal();
	}

	@JsonCreator
	public static Permission fromId(String id) {
		String normalized = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		for (Permission permission : values()) {
			if (permission.id.equals(normalized)) {
				return permission;
			}
		}
		throw new IllegalArgumentException("Unknown permission level: " + id);
	}
}
