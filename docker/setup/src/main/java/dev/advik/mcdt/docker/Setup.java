package dev.advik.mcdt.docker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Prepares /data before the game starts. The entrypoint runs it on every container start.
 * <ol>
 *   <li>Removes the jars that the last start installed (listed in {@code mods/.mcdt-managed}). Jars that the
 *       user put in {@code mods/} are not touched.</li>
 *   <li>Installs the image's jars (MCDT, Fabric API, Baritone if MC_BARITONE=true), the jars in the mounted
 *       /mods folder, the MC_MODS URLs and the MC_MODRINTH projects, and lists them in the manifest.</li>
 *   <li>Writes a light {@code options.txt} if there is none.</li>
 * </ol>
 */
public final class Setup {
	static final String MANIFEST = ".mcdt-managed";
	/** Jars that images before the manifest installed. Removed once, when a volume has no manifest yet. */
	private static final List<String> LEGACY_PREFIXES = List.of("mcdt-", "fabric-api-", "baritone-api-");

	private final SetupOptions options;
	private final Downloader http;
	private final Path modsDir;
	private final Path cacheDir;
	private final Set<String> installed = new LinkedHashSet<>();

	Setup(SetupOptions options, Downloader http) {
		this.options = options;
		this.http = http;
		this.modsDir = options.dataDir().resolve("mods");
		this.cacheDir = options.dataDir().resolve(".mcdt-cache");
	}

	public static void main(String[] args) {
		try {
			new Setup(SetupOptions.fromEnv(System.getenv()), new Downloader()).run();
		} catch (SetupException e) {
			System.err.println("[mcdt-setup] " + e.getMessage());
			System.exit(1);
		}
	}

	void run() {
		try {
			Files.createDirectories(modsDir);
			removeManaged();
			installDir(options.optDir().resolve("mods"), "image");
			if (options.baritone()) {
				installDir(options.optDir().resolve("baritone"), "image");
			}
			installDir(options.userModsDir(), options.userModsDir().toString());
			for (URI url : options.urls()) {
				installUrl(url);
			}
			if (!options.modrinth().isEmpty()) {
				Set<String> provided = "fabric".equals(options.loader()) ? Set.of(Modrinth.FABRIC_API) : Set.of();
				Modrinth modrinth = new Modrinth(http, options.modrinthApi(), options.loader(), options.mcVersion(), provided);
				for (Modrinth.ModFile file : modrinth.resolve(options.modrinth())) {
					installModrinth(file);
				}
			}
			seedOptions();
		} catch (IOException e) {
			throw new SetupException("Setup failed: " + e.getMessage(), e);
		}
		log("Mods from the image and the settings: " + String.join(", ", installed));
	}

	private void removeManaged() throws IOException {
		Path manifest = modsDir.resolve(MANIFEST);
		if (Files.exists(manifest)) {
			for (String name : Files.readAllLines(manifest)) {
				if (!name.isBlank()) {
					Files.deleteIfExists(modsDir.resolve(safeFileName(name.trim())));
				}
			}
		} else {
			try (DirectoryStream<Path> jars = Files.newDirectoryStream(modsDir, "*.jar")) {
				for (Path jar : jars) {
					String name = jar.getFileName().toString();
					if (LEGACY_PREFIXES.stream().anyMatch(name::startsWith)) {
						Files.delete(jar);
					}
				}
			}
		}
		writeManifest();
	}

	private void installDir(Path dir, String source) throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		List<Path> jars = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
			stream.forEach(jars::add);
		}
		jars.sort(null);
		for (Path jar : jars) {
			install(jar, jar.getFileName().toString());
		}
		if (!dir.startsWith(options.optDir()) && !jars.isEmpty()) {
			log("Installed " + jars.size() + " jar(s) from " + source + ".");
		}
	}

	private void installUrl(URI url) throws IOException {
		String name = fileNameOf(url);
		Path cached = cacheDir.resolve("urls").resolve(sha256(url.toString())).resolve(name);
		log("Downloading " + url);
		try {
			http.download(url, cached, null);
		} catch (SetupException e) {
			if (!Files.exists(cached)) {
				throw e;
			}
			log(e.getMessage() + " Using the copy from an earlier start.");
		}
		install(cached, name);
	}

	private void installModrinth(Modrinth.ModFile file) throws IOException {
		Path cached = cacheDir.resolve("modrinth").resolve(file.sha512().substring(0, 32)).resolve(file.filename());
		if (Files.exists(cached) && Downloader.sha512(cached).equalsIgnoreCase(file.sha512())) {
			log("Modrinth: " + file.filename() + " (cached)");
		} else {
			log("Modrinth: downloading " + file.filename());
			http.download(file.url(), cached, file.sha512());
		}
		install(cached, file.filename());
	}

	private void install(Path source, String name) throws IOException {
		Path target = modsDir.resolve(safeFileName(name));
		if (Files.exists(target) && !installed.contains(target.getFileName().toString())) {
			log("Replacing " + target.getFileName() + " in " + modsDir + ".");
		}
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		installed.add(target.getFileName().toString());
		// Written after every jar, so a failed start still removes what it installed on the next start.
		writeManifest();
	}

	private void writeManifest() throws IOException {
		Files.write(modsDir.resolve(MANIFEST), installed);
	}

	private void seedOptions() throws IOException {
		Path target = options.dataDir().resolve("options.txt");
		if (Files.exists(target)) {
			return;
		}
		try (InputStream in = Setup.class.getResourceAsStream("/options.txt")) {
			if (in == null) {
				throw new IllegalStateException("options.txt is missing from the setup jar");
			}
			Files.copy(in, target);
		}
	}

	/** The last path segment of a URL, as a jar file name. */
	static String fileNameOf(URI url) {
		String path = url.getRawPath() == null ? "" : url.getRawPath();
		String name = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
		if (name.isBlank()) {
			throw new SetupException("MC_MODS: cannot take a file name from " + url + ".");
		}
		return safeFileName(name.endsWith(".jar") ? name : name + ".jar");
	}

	/** Rejects names that could leave the mods folder. */
	static String safeFileName(String name) {
		if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
			throw new SetupException("'" + name + "' is not a valid file name.");
		}
		return name;
	}

	private static String sha256(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static void log(String message) {
		System.out.println("[mcdt-setup] " + message);
	}
}
