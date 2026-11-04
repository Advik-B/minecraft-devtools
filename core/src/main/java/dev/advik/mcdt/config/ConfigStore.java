package dev.advik.mcdt.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads and saves {@link McdtConfig}. */
public final class ConfigStore {
	public static final String PORT_ENV = "MCDT_PORT";
	public static final String BIND_ADDRESS_ENV = "MCDT_BIND_ADDRESS";
	public static final String PERMISSION_ENV = "MCDT_PERMISSION";
	public static final String ALLOWED_HOSTS_ENV = "MCDT_ALLOWED_HOSTS";
	public static final String ORIGIN_ALLOWLIST_ENV = "MCDT_ORIGIN_ALLOWLIST";
	public static final String ALLOWED_MULTIPLAYER_SERVERS_ENV = "MCDT_ALLOWED_MULTIPLAYER_SERVERS";
	public static final String ALLOW_QUIT_ENV = "MCDT_ALLOW_QUIT";
	/** A fixed bearer token (at least {@link #MIN_TOKEN_LENGTH} characters) instead of a random per-launch one. */
	public static final String TOKEN_ENV = "MCDT_TOKEN";
	public static final int MIN_TOKEN_LENGTH = 32;

	private final Path file;

	public ConfigStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	/**
	 * Reads the config, filling in defaults for missing fields, and writes it back so new settings appear in the
	 * file. A malformed file is moved to {@code config.json.bak} and replaced with defaults.
	 */
	public McdtConfig load() {
		McdtConfig config = new McdtConfig();
		if (Files.isRegularFile(file)) {
			try {
				config = Json.MAPPER.readValue(Files.readString(file), McdtConfig.class);
			} catch (IOException e) {
				Path backup = file.resolveSibling(file.getFileName() + ".bak");
				Mcdt.LOGGER.warn("MCDT config {} is malformed ({}); moved it to {} and using defaults",
						file, e.getMessage(), backup.getFileName());
				try {
					Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException moveError) {
					Mcdt.LOGGER.warn("Could not back up malformed config", moveError);
				}
				config = new McdtConfig();
			}
		}
		config.normalize();
		save(config);
		return config;
	}

	public void save(McdtConfig config) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	/**
	 * Applies {@code MCDT_*} environment overrides to {@code config} in memory (they are not saved). Lists are
	 * comma-separated. Invalid values are logged and ignored. Returns {@code config}.
	 */
	public static McdtConfig applyEnv(McdtConfig config, Map<String, String> environment) {
		String bind = environment.get(BIND_ADDRESS_ENV);
		if (bind != null && !bind.isBlank()) {
			config.bindAddress = bind.trim();
		}
		String permission = environment.get(PERMISSION_ENV);
		if (permission != null && !permission.isBlank()) {
			try {
				config.permission = Permission.fromId(permission);
			} catch (IllegalArgumentException e) {
				Mcdt.LOGGER.warn("Ignoring invalid {}={}", PERMISSION_ENV, permission);
			}
		}
		String allowQuit = environment.get(ALLOW_QUIT_ENV);
		if (allowQuit != null && !allowQuit.isBlank()) {
			switch (allowQuit.trim().toLowerCase(Locale.ROOT)) {
				case "true", "1", "yes" -> config.allowQuit = true;
				case "false", "0", "no" -> config.allowQuit = false;
				default -> Mcdt.LOGGER.warn("Ignoring invalid {}={}", ALLOW_QUIT_ENV, allowQuit);
			}
		}
		List<String> hosts = listEnv(environment, ALLOWED_HOSTS_ENV);
		if (hosts != null) {
			config.allowedHosts = hosts;
		}
		List<String> origins = listEnv(environment, ORIGIN_ALLOWLIST_ENV);
		if (origins != null) {
			config.originAllowlist = origins;
		}
		List<String> servers = listEnv(environment, ALLOWED_MULTIPLAYER_SERVERS_ENV);
		if (servers != null) {
			config.allowedMultiplayerServers = servers;
		}
		return config.normalize();
	}

	private static List<String> listEnv(Map<String, String> environment, String name) {
		String value = environment.get(name);
		if (value == null || value.isBlank()) {
			return null;
		}
		List<String> items = new ArrayList<>();
		for (String item : value.split(",")) {
			if (!item.isBlank()) {
				items.add(item.trim());
			}
		}
		return items;
	}

	/** The port to try first: {@code MCDT_PORT} if set and valid, otherwise the configured port. */
	public static int effectivePort(McdtConfig config, Map<String, String> environment) {
		String override = environment.get(PORT_ENV);
		if (override != null && !override.isBlank()) {
			try {
				int port = Integer.parseInt(override.trim());
				if (port >= 0 && port <= 65_535) {
					return port;
				}
			} catch (NumberFormatException ignored) {
				// fall through
			}
			Mcdt.LOGGER.warn("Ignoring invalid {}={}", PORT_ENV, override);
		}
		return config.preferredPort;
	}
}
