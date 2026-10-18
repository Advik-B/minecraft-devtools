package dev.advik.mcdt.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Loads and saves {@link McdtConfig}. */
public final class ConfigStore {
	public static final String PORT_ENV = "MCDT_PORT";

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
