package dev.advik.mcdt.docker;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything setup reads from the environment and from the image's {@code node.env}.
 *
 * @param dataDir     the game directory (the /data volume)
 * @param optDir      the image's MCDT files: node.env, mods/, baritone/
 * @param userModsDir jars the user mounts (/mods); may be missing
 * @param loader      fabric or neoforge
 * @param mcVersion   the Minecraft version, for example 1.21.4
 * @param baritone    true installs the bundled Baritone jar
 * @param urls        MC_MODS: jar URLs to download
 * @param modrinth    MC_MODRINTH: Modrinth projects to install
 * @param modrinthApi the Modrinth API base, for example https://api.modrinth.com/v2
 */
public record SetupOptions(
		Path dataDir,
		Path optDir,
		Path userModsDir,
		String loader,
		String mcVersion,
		boolean baritone,
		List<URI> urls,
		List<ModrinthSpec> modrinth,
		URI modrinthApi
) {
	public static final String DEFAULT_MODRINTH_API = "https://api.modrinth.com/v2";

	/** Reads the options from environment variables, as the entrypoint passes them. */
	public static SetupOptions fromEnv(Map<String, String> env) {
		Path optDir = Path.of(env.getOrDefault("MCDT_OPT_DIR", "/opt/mcdt"));
		Map<String, String> node = readNodeEnv(optDir.resolve("node.env"));

		String auth = env.getOrDefault("MC_AUTH", "offline");
		if (!auth.equals("offline") && !auth.equals("microsoft")) {
			throw new SetupException("MC_AUTH must be 'offline' or 'microsoft', got '" + auth + "'.");
		}
		String baritone = env.getOrDefault("MC_BARITONE", "true");
		if (!baritone.equals("true") && !baritone.equals("false")) {
			throw new SetupException("MC_BARITONE must be 'true' or 'false', got '" + baritone + "'.");
		}

		return new SetupOptions(
				Path.of(env.getOrDefault("MCDT_DATA_DIR", "/data")),
				optDir,
				Path.of(env.getOrDefault("MCDT_USER_MODS_DIR", "/mods")),
				require(node, "LOADER"),
				require(node, "MC_VERSION"),
				baritone.equals("true"),
				parseUrls(env.getOrDefault("MC_MODS", "")),
				parseModrinth(env.getOrDefault("MC_MODRINTH", "")),
				URI.create(env.getOrDefault("MCDT_MODRINTH_API", DEFAULT_MODRINTH_API))
		);
	}

	/** Splits on commas and whitespace. */
	static List<String> split(String value) {
		List<String> out = new ArrayList<>();
		for (String part : value.split("[,\\s]+")) {
			if (!part.isBlank()) {
				out.add(part.trim());
			}
		}
		return out;
	}

	static List<URI> parseUrls(String value) {
		List<URI> out = new ArrayList<>();
		for (String part : split(value)) {
			URI uri;
			try {
				uri = URI.create(part);
			} catch (IllegalArgumentException e) {
				throw new SetupException("MC_MODS: '" + part + "' is not a valid URL.");
			}
			if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
				throw new SetupException("MC_MODS: '" + part + "' must be an http or https URL.");
			}
			out.add(uri);
		}
		return out;
	}

	static List<ModrinthSpec> parseModrinth(String value) {
		List<ModrinthSpec> out = new ArrayList<>();
		for (String part : split(value)) {
			out.add(ModrinthSpec.parse(part));
		}
		return out;
	}

	static Map<String, String> readNodeEnv(Path file) {
		Map<String, String> out = new HashMap<>();
		try {
			for (String line : Files.readAllLines(file)) {
				int eq = line.indexOf('=');
				if (eq > 0 && !line.startsWith("#")) {
					out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
				}
			}
		} catch (IOException e) {
			throw new SetupException("Cannot read " + file + ": " + e.getMessage(), e);
		}
		return out;
	}

	private static String require(Map<String, String> node, String key) {
		String value = node.get(key);
		if (value == null || value.isEmpty()) {
			throw new SetupException("node.env has no " + key + ".");
		}
		return value;
	}
}
