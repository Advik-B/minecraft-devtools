package dev.advik.mcdt.docker;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves MC_MODRINTH projects to files for one loader and Minecraft version, with their required
 * dependencies. Projects that the image already bundles are skipped.
 */
final class Modrinth {
	/** The Fabric API project. The Fabric images bundle it. */
	static final String FABRIC_API = "P7dR8mSH";

	/** One file to install. */
	record ModFile(String projectId, String versionNumber, String filename, URI url, String sha512) {
	}

	private final Downloader http;
	private final URI api;
	private final String loader;
	private final String mcVersion;
	private final Set<String> provided;

	Modrinth(Downloader http, URI api, String loader, String mcVersion, Set<String> provided) {
		this.http = http;
		this.api = api;
		this.loader = loader;
		this.mcVersion = mcVersion;
		this.provided = provided;
	}

	/** Returns the files for {@code specs} and every required dependency, one file per project. */
	List<ModFile> resolve(List<ModrinthSpec> specs) {
		Map<String, ModFile> chosen = new LinkedHashMap<>();
		for (ModrinthSpec spec : specs) {
			add(pick(spec.project(), spec.version(), spec.toString()), chosen, spec.toString());
		}
		return new ArrayList<>(chosen.values());
	}

	private void add(JsonNode version, Map<String, ModFile> chosen, String requestedBy) {
		String projectId = version.path("project_id").asText();
		if (provided.contains(projectId)) {
			Setup.log("Modrinth: " + requestedBy + " is already in the image, skipped.");
			return;
		}
		if (chosen.containsKey(projectId)) {
			return;
		}
		chosen.put(projectId, primaryFile(version));
		for (JsonNode dep : version.path("dependencies")) {
			if (!"required".equals(dep.path("dependency_type").asText())) {
				continue;
			}
			String depProject = dep.path("project_id").asText(null);
			String depVersion = dep.path("version_id").asText(null);
			if (depProject != null && (provided.contains(depProject) || chosen.containsKey(depProject))) {
				continue;
			}
			String label = "dependency of " + requestedBy;
			JsonNode resolved = depVersion != null ? version(depVersion, label) : pick(depProject, null, label);
			add(resolved, chosen, label);
		}
	}

	/** The pinned version (number or id), or the newest one for this loader and Minecraft version. */
	private JsonNode pick(String project, String wanted, String label) {
		String query = "loaders=" + encode("[\"" + loader + "\"]") + "&game_versions=" + encode("[\"" + mcVersion + "\"]");
		JsonNode versions = http.getJson(URI.create(api + "/project/" + encode(project) + "/version?" + query));
		if (versions == null) {
			throw new SetupException("Modrinth: no project '" + project + "' (" + label + ").");
		}
		if (!versions.isArray() || versions.isEmpty()) {
			throw new SetupException("Modrinth: '" + project + "' has no version for " + loader + " " + mcVersion + " (" + label + ").");
		}
		if (wanted == null) {
			return versions.get(0);
		}
		for (JsonNode version : versions) {
			if (wanted.equals(version.path("version_number").asText()) || wanted.equals(version.path("id").asText())) {
				return version;
			}
		}
		throw new SetupException("Modrinth: '" + project + "' has no version '" + wanted + "' for " + loader + " " + mcVersion + ".");
	}

	private JsonNode version(String id, String label) {
		JsonNode version = http.getJson(URI.create(api + "/version/" + encode(id)));
		if (version == null) {
			throw new SetupException("Modrinth: no version '" + id + "' (" + label + ").");
		}
		return version;
	}

	private static ModFile primaryFile(JsonNode version) {
		JsonNode files = version.path("files");
		JsonNode file = null;
		for (JsonNode candidate : files) {
			if (candidate.path("primary").asBoolean(false)) {
				file = candidate;
				break;
			}
		}
		if (file == null && files.size() > 0) {
			file = files.get(0);
		}
		if (file == null) {
			throw new SetupException("Modrinth: version " + version.path("id").asText() + " has no files.");
		}
		String sha512 = file.path("hashes").path("sha512").asText(null);
		if (sha512 == null) {
			throw new SetupException("Modrinth: " + file.path("filename").asText() + " has no SHA-512.");
		}
		return new ModFile(
				version.path("project_id").asText(),
				version.path("version_number").asText(),
				Setup.safeFileName(file.path("filename").asText()),
				URI.create(file.path("url").asText()),
				sha512
		);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
