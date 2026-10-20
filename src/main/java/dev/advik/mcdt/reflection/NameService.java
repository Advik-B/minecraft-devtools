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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles translation between Mojang names and runtime intermediary/obfuscated names.
 * Downloads and caches official client mappings from Mojang if in production environment.
 */
public final class NameService {
	private static final String MOJANG_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	/** Fabric's namespace for the obfuscated names that Mojang's mappings map to. */
	private static final String OFFICIAL_NAMESPACE = "official";
	private static final Pattern METHOD_LINE = Pattern.compile("^(?:\\d+:\\d+:)?(\\S+) ([^\\s(]+)\\(([^)]*)\\)(?::\\d+:\\d+)?$");

	private final Map<String, String> classIntermediaryToMojang = new ConcurrentHashMap<>();
	private final Map<String, String> classMojangToIntermediary = new ConcurrentHashMap<>();

	private final Map<String, String> fieldIntermediaryToMojang = new ConcurrentHashMap<>();
	private final Map<String, String> fieldMojangToIntermediary = new ConcurrentHashMap<>();

	private final Map<String, String> methodIntermediaryToMojang = new ConcurrentHashMap<>();
	private final Map<String, String> methodMojangToIntermediary = new ConcurrentHashMap<>();

	private boolean mappingsLoaded = false;
	private boolean isDevEnvironment = true;
	private final RuntimeNames runtimeNames;

	public NameService() {
		detectEnvironment();
		this.runtimeNames = isDevEnvironment ? RuntimeNames.IDENTITY : fabricRuntimeNames();
	}

	/** For tests: a production-mode service with the given obfuscated-to-runtime translation. */
	NameService(RuntimeNames runtimeNames) {
		this.isDevEnvironment = false;
		this.runtimeNames = runtimeNames;
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
	 *     [line:line:]type mojangMethod(...)[:line:line] -> obfMethod
	 * Obfuscated names are translated to the runtime namespace with {@link RuntimeNames}. Fields and methods are
	 * looked up by their obfuscated descriptor, so classes are read in a first pass.
	 */
	public void parseProGuardMappings(BufferedReader reader) throws IOException {
		List<String> lines = reader.lines().toList();

		Map<String, String> mojangToObf = new HashMap<>();
		for (String line : lines) {
			if (!line.startsWith(" ") && !line.startsWith("#")) {
				int arrow = line.indexOf(" -> ");
				String trimmed = line.stripTrailing();
				if (arrow != -1 && trimmed.endsWith(":")) {
					mojangToObf.put(line.substring(0, arrow).trim(), trimmed.substring(arrow + 4, trimmed.length() - 1).trim());
				}
			}
		}

		String currentMojangClass = null;
		String currentObfClass = null;
		String currentRuntimeClass = null;

		for (String rawLine : lines) {
			String line = rawLine.stripTrailing();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			if (!line.startsWith(" ")) {
				// Class line: e.g. "net.minecraft.client.Minecraft -> fgt:"
				int arrow = line.indexOf(" -> ");
				if (arrow != -1 && line.endsWith(":")) {
					currentMojangClass = line.substring(0, arrow).trim();
					currentObfClass = line.substring(arrow + 4, line.length() - 1).trim();
					currentRuntimeClass = mapOr(runtimeNames.mapClass(currentObfClass), currentObfClass);

					classIntermediaryToMojang.put(currentRuntimeClass, currentMojangClass);
					classMojangToIntermediary.put(currentMojangClass, currentRuntimeClass);
				}
				continue;
			}
			if (currentMojangClass == null) {
				continue;
			}

			// Member line
			String trimmed = line.trim();
			int arrow = trimmed.indexOf(" -> ");
			if (arrow == -1) {
				continue;
			}
			String left = trimmed.substring(0, arrow).trim();
			String obfMember = trimmed.substring(arrow + 4).trim();

			Matcher method = METHOD_LINE.matcher(left);
			if (method.matches()) {
				// Method: e.g. "15:16:void setScreen(net.minecraft.client.gui.screens.Screen):17:18"
				String mojangMethodName = method.group(2);
				if (mojangMethodName.startsWith("<")) {
					continue;
				}
				StringBuilder desc = new StringBuilder("(");
				String params = method.group(3);
				if (!params.isBlank()) {
					for (String param : params.split(",")) {
						desc.append(descriptor(param.trim(), mojangToObf));
					}
				}
				desc.append(')').append(descriptor(method.group(1), mojangToObf));
				String runtimeMethod = mapOr(runtimeNames.mapMethod(currentObfClass, obfMember, desc.toString()), obfMember);

				methodIntermediaryToMojang.put(currentRuntimeClass + "#" + runtimeMethod, mojangMethodName);
				methodMojangToIntermediary.put(currentRuntimeClass + "#" + mojangMethodName, runtimeMethod);
			} else if (!left.contains("(")) {
				// Field: e.g. "net.minecraft.client.player.LocalPlayer player"
				int lastSpace = left.lastIndexOf(' ');
				if (lastSpace != -1) {
					String mojangFieldName = left.substring(lastSpace + 1).trim();
					String desc = descriptor(left.substring(0, lastSpace).trim(), mojangToObf);
					String runtimeField = mapOr(runtimeNames.mapField(currentObfClass, obfMember, desc), obfMember);

					fieldIntermediaryToMojang.put(currentRuntimeClass + "#" + runtimeField, mojangFieldName);
					fieldMojangToIntermediary.put(currentRuntimeClass + "#" + mojangFieldName, runtimeField);
				}
			}
		}
	}

	/** JVM descriptor of a ProGuard type name, with Minecraft classes replaced by their obfuscated names. */
	static String descriptor(String type, Map<String, String> mojangToObf) {
		int dimensions = 0;
		while (type.endsWith("[]")) {
			dimensions++;
			type = type.substring(0, type.length() - 2);
		}
		String base = switch (type) {
			case "void" -> "V";
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			default -> "L" + mojangToObf.getOrDefault(type, type).replace('.', '/') + ";";
		};
		return "[".repeat(dimensions) + base;
	}

	private static String mapOr(String mapped, String fallback) {
		return mapped != null ? mapped : fallback;
	}

	/** Translates obfuscated ({@code official}) names into the runtime namespace. */
	interface RuntimeNames {
		RuntimeNames IDENTITY = new RuntimeNames() {
			@Override
			public String mapClass(String obfClass) {
				return obfClass;
			}

			@Override
			public String mapField(String obfOwner, String obfName, String obfDescriptor) {
				return obfName;
			}

			@Override
			public String mapMethod(String obfOwner, String obfName, String obfDescriptor) {
				return obfName;
			}
		};

		String mapClass(String obfClass);

		String mapField(String obfOwner, String obfName, String obfDescriptor);

		String mapMethod(String obfOwner, String obfName, String obfDescriptor);
	}

	private static RuntimeNames fabricRuntimeNames() {
		try {
			MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
			if (!resolver.getNamespaces().contains(OFFICIAL_NAMESPACE)) {
				return RuntimeNames.IDENTITY;
			}
			return new RuntimeNames() {
				@Override
				public String mapClass(String obfClass) {
					return resolver.mapClassName(OFFICIAL_NAMESPACE, obfClass);
				}

				@Override
				public String mapField(String obfOwner, String obfName, String obfDescriptor) {
					return resolver.mapFieldName(OFFICIAL_NAMESPACE, obfOwner, obfName, obfDescriptor);
				}

				@Override
				public String mapMethod(String obfOwner, String obfName, String obfDescriptor) {
					return resolver.mapMethodName(OFFICIAL_NAMESPACE, obfOwner, obfName, obfDescriptor);
				}
			};
		} catch (Throwable ignored) {
			return RuntimeNames.IDENTITY;
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
