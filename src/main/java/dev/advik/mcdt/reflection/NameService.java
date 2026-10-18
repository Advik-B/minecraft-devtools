package dev.advik.mcdt.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles translation between Mojang names and runtime intermediary/obfuscated names.
 * Downloads and caches official client mappings from Mojang if in production environment.
 */
public final class NameService {
	private static final String MOJANG_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

	private final Map<String, String> classIntermediaryToMojang = new ConcurrentHashMap<>();
	private final Map<String, String> classMojangToIntermediary = new ConcurrentHashMap<>();

	private final Map<String, String> fieldIntermediaryToMojang = new ConcurrentHashMap<>();
	private final Map<String, String> fieldMojangToIntermediary = new ConcurrentHashMap<>();

	private final Map<String, String> methodIntermediaryToMojang = new ConcurrentHashMap<>();
	private final Map<String, String> methodMojangToIntermediary = new ConcurrentHashMap<>();

	private boolean mappingsLoaded = false;
	private boolean isDevEnvironment = true;

	public NameService() {
		detectEnvironment();
	}

	private void detectEnvironment() {
		try {
			MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
			String ns = resolver.getCurrentRuntimeNamespace();
			isDevEnvironment = "named".equals(ns);
		} catch (Throwable ignored) {
			isDevEnvironment = true;
		}
	}

	public boolean isDevEnvironment() {
		return isDevEnvironment;
	}

	public boolean areMappingsLoaded() {
		return mappingsLoaded;
	}

	/**
	 * Ensures mappings are loaded, downloading them to configDir/mappings if in production.
	 */
	public synchronized void initialize(Path configDir, String gameVersion) {
		if (mappingsLoaded) {
			return;
		}

		if (isDevEnvironment) {
			mappingsLoaded = true;
			return;
		}

		Path mappingsFile = configDir.resolve("mappings").resolve(gameVersion + "-client.txt");
		if (Files.exists(mappingsFile)) {
			try (BufferedReader reader = Files.newBufferedReader(mappingsFile, StandardCharsets.UTF_8)) {
				parseProGuardMappings(reader);
				mappingsLoaded = true;
				Mcdt.LOGGER.info("Loaded Mojang mappings from cached file: {}", mappingsFile);
				return;
			} catch (Exception e) {
				Mcdt.LOGGER.warn("Failed to read cached mappings from {}: {}", mappingsFile, e.getMessage());
			}
		}

		// Download mappings if missing
		try {
			downloadAndLoadMappings(mappingsFile, gameVersion);
			mappingsLoaded = true;
		} catch (Exception e) {
			Mcdt.LOGGER.warn("Could not download Mojang mappings for {}: {}. Falling back to runtime names.", gameVersion, e.getMessage());
		}
	}

	public synchronized void loadFromReader(BufferedReader reader) throws IOException {
		parseProGuardMappings(reader);
		mappingsLoaded = true;
	}

	private void downloadAndLoadMappings(Path targetFile, String gameVersion) throws Exception {
		Files.createDirectories(targetFile.getParent());
		Mcdt.LOGGER.info("Fetching Mojang version manifest to locate {} client mappings...", gameVersion);

		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();

		HttpRequest manifestReq = HttpRequest.newBuilder()
				.uri(URI.create(MOJANG_MANIFEST_URL))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		HttpResponse<String> manifestResp = httpClient.send(manifestReq, HttpResponse.BodyHandlers.ofString());
		if (manifestResp.statusCode() != 200) {
			throw new IOException("Failed to fetch version manifest, HTTP " + manifestResp.statusCode());
		}

		JsonNode root = Json.MAPPER.readTree(manifestResp.body());
		JsonNode versions = root.path("versions");
		String versionUrl = null;
		for (JsonNode node : versions) {
			if (gameVersion.equals(node.path("id").asText())) {
				versionUrl = node.path("url").asText();
				break;
			}
		}

		if (versionUrl == null) {
			throw new IOException("Version " + gameVersion + " not found in Mojang version manifest");
		}

		HttpRequest versionReq = HttpRequest.newBuilder()
				.uri(URI.create(versionUrl))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

		HttpResponse<String> versionResp = httpClient.send(versionReq, HttpResponse.BodyHandlers.ofString());
		if (versionResp.statusCode() != 200) {
			throw new IOException("Failed to fetch version package JSON, HTTP " + versionResp.statusCode());
		}

		JsonNode versionNode = Json.MAPPER.readTree(versionResp.body());
		String mappingsUrl = versionNode.path("downloads").path("client_mappings").path("url").asText();
		if (mappingsUrl == null || mappingsUrl.isBlank()) {
			throw new IOException("client_mappings URL not present for " + gameVersion);
		}

		Mcdt.LOGGER.info("Downloading official {} client mappings from Mojang...", gameVersion);
		HttpRequest mappingsReq = HttpRequest.newBuilder()
				.uri(URI.create(mappingsUrl))
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();

		HttpResponse<Path> mappingsResp = httpClient.send(mappingsReq, HttpResponse.BodyHandlers.ofFile(targetFile));
		if (mappingsResp.statusCode() != 200) {
			throw new IOException("Failed to download client mappings, HTTP " + mappingsResp.statusCode());
		}

		try (BufferedReader reader = Files.newBufferedReader(targetFile, StandardCharsets.UTF_8)) {
			parseProGuardMappings(reader);
		}
		Mcdt.LOGGER.info("Mojang mappings downloaded and cached successfully.");
	}

	/**
	 * Parses ProGuard format lines:
	 * mojangClass -> obfClass:
	 *     type mojangField -> obfField
	 *     [line:]type mojangMethod(...) -> obfMethod
	 */
	public void parseProGuardMappings(BufferedReader reader) throws IOException {
		MappingResolver resolver = null;
		try {
			resolver = FabricLoader.getInstance().getMappingResolver();
		} catch (Throwable ignored) {
		}

		String line;
		String currentMojangClass = null;
		String currentObfClass = null;
		String currentIntermediaryClass = null;

		while ((line = reader.readLine()) != null) {
			line = line.stripTrailing();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			if (!line.startsWith(" ")) {
				// Class line: e.g. "net.minecraft.client.Minecraft -> fgt:"
				int arrow = line.indexOf(" -> ");
				if (arrow != -1 && line.endsWith(":")) {
					currentMojangClass = line.substring(0, arrow).trim();
					currentObfClass = line.substring(arrow + 4, line.length() - 1).trim();

					if (resolver != null) {
						currentIntermediaryClass = resolver.mapClassName("intermediary", currentObfClass);
					} else {
						currentIntermediaryClass = currentObfClass;
					}

					classIntermediaryToMojang.put(currentIntermediaryClass, currentMojangClass);
					classMojangToIntermediary.put(currentMojangClass, currentIntermediaryClass);
				}
			} else if (currentMojangClass != null) {
				// Member line
				String trimmed = line.trim();
				int arrow = trimmed.indexOf(" -> ");
				if (arrow == -1) {
					continue;
				}

				String left = trimmed.substring(0, arrow).trim();
				String obfMember = trimmed.substring(arrow + 4).trim();

				if (left.contains("(") && left.contains(")")) {
					// Method: e.g. "void setScreen(net.minecraft.client.gui.screens.Screen)" or "15:16:void setScreen(...)"
					int colon = left.lastIndexOf(':');
					String methodPart = (colon != -1) ? left.substring(colon + 1).trim() : left;
					int openParen = methodPart.indexOf('(');
					if (openParen != -1) {
						String beforeParen = methodPart.substring(0, openParen).trim();
						int lastSpace = beforeParen.lastIndexOf(' ');
						if (lastSpace != -1) {
							String mojangMethodName = beforeParen.substring(lastSpace + 1).trim();

							String intermediaryMethod = obfMember;
							if (resolver != null && currentObfClass != null) {
								try {
									// Best effort resolution via intermediary
									intermediaryMethod = resolver.mapMethodName("intermediary", currentObfClass, obfMember, "");
								} catch (Throwable ignored) {
								}
							}

							methodIntermediaryToMojang.put(currentIntermediaryClass + "#" + intermediaryMethod, mojangMethodName);
							methodMojangToIntermediary.put(currentMojangClass + "#" + mojangMethodName, intermediaryMethod);
						}
					}
				} else {
					// Field: e.g. "net.minecraft.client.player.LocalPlayer player"
					int lastSpace = left.lastIndexOf(' ');
					if (lastSpace != -1) {
						String mojangFieldName = left.substring(lastSpace + 1).trim();
						String intermediaryField = obfMember;
						if (resolver != null && currentObfClass != null) {
							try {
								intermediaryField = resolver.mapFieldName("intermediary", currentObfClass, obfMember, "");
							} catch (Throwable ignored) {
							}
						}

						fieldIntermediaryToMojang.put(currentIntermediaryClass + "#" + intermediaryField, mojangFieldName);
						fieldMojangToIntermediary.put(currentMojangClass + "#" + mojangFieldName, intermediaryField);
					}
				}
			}
		}
	}

	public String resolveClassName(String runtimeClassName) {
		if (runtimeClassName == null) {
			return null;
		}
		if (isDevEnvironment) {
			return runtimeClassName;
		}
		return classIntermediaryToMojang.getOrDefault(runtimeClassName, runtimeClassName);
	}

	public String toRuntimeClassName(String mojangClassName) {
		if (mojangClassName == null) {
			return null;
		}
		if (isDevEnvironment) {
			return mojangClassName;
		}
		return classMojangToIntermediary.getOrDefault(mojangClassName, mojangClassName);
	}

	public String resolveFieldName(Class<?> owner, String runtimeFieldName) {
		if (runtimeFieldName == null) {
			return null;
		}
		if (isDevEnvironment) {
			return runtimeFieldName;
		}
		if (owner != null) {
			String mapped = fieldIntermediaryToMojang.get(owner.getName() + "#" + runtimeFieldName);
			if (mapped != null) {
				return mapped;
			}
		}
		return runtimeFieldName;
	}

	public String toRuntimeFieldName(Class<?> owner, String mojangFieldName) {
		if (mojangFieldName == null) {
			return null;
		}
		if (isDevEnvironment) {
			return mojangFieldName;
		}
		if (owner != null) {
			String mapped = fieldMojangToIntermediary.get(owner.getName() + "#" + mojangFieldName);
			if (mapped != null) {
				return mapped;
			}
		}
		return mojangFieldName;
	}

	public String resolveMethodName(Class<?> owner, String runtimeMethodName) {
		if (runtimeMethodName == null) {
			return null;
		}
		if (isDevEnvironment) {
			return runtimeMethodName;
		}
		if (owner != null) {
			String mapped = methodIntermediaryToMojang.get(owner.getName() + "#" + runtimeMethodName);
			if (mapped != null) {
				return mapped;
			}
		}
		return runtimeMethodName;
	}

	public String toRuntimeMethodName(Class<?> owner, String mojangMethodName) {
		if (mojangMethodName == null) {
			return null;
		}
		if (isDevEnvironment) {
			return mojangMethodName;
		}
		if (owner != null) {
			String mapped = methodMojangToIntermediary.get(owner.getName() + "#" + mojangMethodName);
			if (mapped != null) {
				return mapped;
			}
		}
		return mojangMethodName;
	}
}
