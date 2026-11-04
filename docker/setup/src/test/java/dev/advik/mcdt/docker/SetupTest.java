package dev.advik.mcdt.docker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SetupTest {
	@TempDir
	Path tmp;

	private HttpServer server;
	private String base;
	private final Map<String, byte[]> routes = new HashMap<>();
	private Path data;
	private Path opt;
	private Path userMods;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String key = exchange.getRequestURI().getRawPath()
					+ (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
			byte[] body = routes.get(key);
			if (body == null) {
				body = routes.get(exchange.getRequestURI().getRawPath());
			}
			if (body == null) {
				exchange.sendResponseHeaders(404, -1);
			} else {
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
			exchange.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();

		data = Files.createDirectories(tmp.resolve("data"));
		opt = Files.createDirectories(tmp.resolve("opt"));
		userMods = tmp.resolve("user-mods");
		Files.createDirectories(opt.resolve("mods"));
		Files.createDirectories(opt.resolve("baritone"));
		Files.writeString(opt.resolve("mods/mcdt-1.1.0+1.21.4-fabric.jar"), "mcdt");
		Files.writeString(opt.resolve("mods/fabric-api-0.119.4.jar"), "fabric-api");
		Files.writeString(opt.resolve("baritone/baritone-api-fabric-1.13.1.jar"), "baritone");
		Files.writeString(opt.resolve("node.env"), "NODE=1.21.4-fabric\nLOADER=fabric\nMC_VERSION=1.21.4\n");
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private Map<String, String> env(String... pairs) {
		Map<String, String> env = new HashMap<>();
		env.put("MCDT_OPT_DIR", opt.toString());
		env.put("MCDT_DATA_DIR", data.toString());
		env.put("MCDT_USER_MODS_DIR", userMods.toString());
		env.put("MCDT_MODRINTH_API", base + "/v2");
		for (int i = 0; i < pairs.length; i += 2) {
			env.put(pairs[i], pairs[i + 1]);
		}
		return env;
	}

	private void run(String... pairs) {
		new Setup(SetupOptions.fromEnv(env(pairs)), new Downloader()).run();
	}

	private List<String> mods() throws IOException {
		try (var files = Files.list(data.resolve("mods"))) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).sorted().toList();
		}
	}

	private List<String> manifest() throws IOException {
		return Files.readAllLines(data.resolve("mods").resolve(Setup.MANIFEST));
	}

	private static String version(String id, String project, String number, String filename, byte[] jar, String deps) throws IOException {
		return """
				{"id":"%s","project_id":"%s","version_number":"%s",
				 "files":[{"url":"%s","filename":"%s","primary":true,"hashes":{"sha512":"%s"}}],
				 "dependencies":[%s]}"""
				.formatted(id, project, number, "URL/" + filename, filename, sha512(jar), deps);
	}

	private static String sha512(byte[] bytes) throws IOException {
		Path file = Files.createTempFile("sha", ".bin");
		try {
			Files.write(file, bytes);
			return Downloader.sha512(file);
		} finally {
			Files.delete(file);
		}
	}

	private void modrinthProject(String slug, String... versions) {
		String query = "loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.4%22%5D";
		routes.put("/v2/project/" + slug + "/version?" + query,
				("[" + String.join(",", versions) + "]").replace("URL/", base + "/files/").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void installsImageModsAndBaritoneByDefault() throws IOException {
		run();
		assertEquals(List.of("baritone-api-fabric-1.13.1.jar", "fabric-api-0.119.4.jar", "mcdt-1.1.0+1.21.4-fabric.jar"), mods());
		assertTrue(Files.exists(data.resolve("options.txt")));
	}

	@Test
	void baritoneFalseLeavesBaritoneOut() throws IOException {
		run("MC_BARITONE", "false");
		assertFalse(mods().contains("baritone-api-fabric-1.13.1.jar"));
	}

	@Test
	void rejectsBadSettings() {
		assertThrows(SetupException.class, () -> SetupOptions.fromEnv(env("MC_BARITONE", "yes")));
		assertThrows(SetupException.class, () -> SetupOptions.fromEnv(env("MC_AUTH", "mojang")));
		assertThrows(SetupException.class, () -> SetupOptions.fromEnv(env("MC_MODS", "ftp://example.com/a.jar")));
		assertThrows(SetupException.class, () -> SetupOptions.fromEnv(env("MC_MODRINTH", "yacl:")));
	}

	@Test
	void parsesModrinthSpecs() {
		assertEquals(List.of(new ModrinthSpec("sodium", null), new ModrinthSpec("yacl", "3.8.1+1.21.4-fabric")),
				SetupOptions.parseModrinth("sodium, yacl:3.8.1+1.21.4-fabric"));
	}

	@Test
	void keepsUserJarsAndRemovesWhatItInstalledBefore() throws IOException {
		Files.createDirectories(userMods);
		Files.writeString(userMods.resolve("extra.jar"), "extra");
		run();
		assertTrue(mods().contains("extra.jar"));

		Files.writeString(data.resolve("mods/my-own.jar"), "mine");
		Files.delete(userMods.resolve("extra.jar"));
		run("MC_BARITONE", "false");

		assertEquals(List.of("fabric-api-0.119.4.jar", "mcdt-1.1.0+1.21.4-fabric.jar", "my-own.jar"), mods());
		assertFalse(manifest().contains("my-own.jar"));
	}

	@Test
	void migratesVolumesWithoutManifest() throws IOException {
		Path mods = Files.createDirectories(data.resolve("mods"));
		Files.writeString(mods.resolve("mcdt-1.0.0-mc_1.21.4.jar"), "old");
		Files.writeString(mods.resolve("baritone-api-fabric-1.12.0.jar"), "old");
		Files.writeString(mods.resolve("sodium.jar"), "user");
		run("MC_BARITONE", "false");
		assertEquals(List.of("fabric-api-0.119.4.jar", "mcdt-1.1.0+1.21.4-fabric.jar", "sodium.jar"), mods());
	}

	@Test
	void downloadsUrlModsAndFallsBackToTheCache() throws IOException {
		routes.put("/dl/some%20mod.jar", "url-mod".getBytes(StandardCharsets.UTF_8));
		run("MC_MODS", base + "/dl/some%20mod.jar");
		assertTrue(mods().contains("some mod.jar"));

		routes.clear();
		run("MC_MODS", base + "/dl/some%20mod.jar");
		assertEquals("url-mod", Files.readString(data.resolve("mods/some mod.jar")));
	}

	@Test
	void failsOnAMissingUrl() {
		assertThrows(SetupException.class, () -> run("MC_MODS", base + "/dl/missing.jar"));
	}

	@Test
	void installsPinnedModrinthVersionWithDependenciesButNotFabricApi() throws IOException {
		byte[] yaclNew = "yacl-3.8.2".getBytes(StandardCharsets.UTF_8);
		byte[] yaclOld = "yacl-3.8.1".getBytes(StandardCharsets.UTF_8);
		byte[] lib = "lib".getBytes(StandardCharsets.UTF_8);
		String deps = """
				{"project_id":"P7dR8mSH","version_id":null,"dependency_type":"required"},
				{"project_id":"LIB","version_id":"libv1","dependency_type":"required"},
				{"project_id":"OPT","version_id":null,"dependency_type":"optional"}""";
		modrinthProject("yacl",
				version("v382", "YACL", "3.8.2+1.21.4-fabric", "yacl-3.8.2.jar", yaclNew, deps),
				version("v381", "YACL", "3.8.1+1.21.4-fabric", "yacl-3.8.1.jar", yaclOld, deps));
		routes.put("/v2/version/libv1", version("libv1", "LIB", "1.0", "lib-1.0.jar", lib, "")
				.replace("URL/", base + "/files/").getBytes(StandardCharsets.UTF_8));
		routes.put("/files/yacl-3.8.2.jar", yaclNew);
		routes.put("/files/yacl-3.8.1.jar", yaclOld);
		routes.put("/files/lib-1.0.jar", lib);

		run("MC_MODRINTH", "yacl:3.8.1+1.21.4-fabric");
		assertTrue(mods().containsAll(List.of("yacl-3.8.1.jar", "lib-1.0.jar")));
		assertFalse(mods().contains("yacl-3.8.2.jar"));

		run("MC_MODRINTH", "yacl");
		assertTrue(mods().contains("yacl-3.8.2.jar"));
		assertFalse(mods().contains("yacl-3.8.1.jar"));
	}

	@Test
	void usesTheModrinthCacheOnLaterStarts() throws IOException {
		byte[] jar = "cached".getBytes(StandardCharsets.UTF_8);
		modrinthProject("thing", version("t1", "THING", "1.0", "thing-1.0.jar", jar, ""));
		routes.put("/files/thing-1.0.jar", jar);
		run("MC_MODRINTH", "thing");
		routes.remove("/files/thing-1.0.jar");
		run("MC_MODRINTH", "thing");
		assertTrue(mods().contains("thing-1.0.jar"));
	}

	@Test
	void rejectsAModrinthFileWithTheWrongHash() throws IOException {
		modrinthProject("bad", version("b1", "BAD", "1.0", "bad-1.0.jar", "expected".getBytes(StandardCharsets.UTF_8), ""));
		routes.put("/files/bad-1.0.jar", "tampered".getBytes(StandardCharsets.UTF_8));
		SetupException e = assertThrows(SetupException.class, () -> run("MC_MODRINTH", "bad"));
		assertTrue(e.getMessage().contains("SHA-512 mismatch"), e.getMessage());
	}

	@Test
	void reportsUnknownProjectsAndVersions() throws IOException {
		modrinthProject("real", version("r1", "REAL", "1.0", "real.jar", new byte[0], ""));
		assertTrue(assertThrows(SetupException.class, () -> run("MC_MODRINTH", "nope")).getMessage().contains("no project"));
		assertTrue(assertThrows(SetupException.class, () -> run("MC_MODRINTH", "real:9.9")).getMessage().contains("no version '9.9'"));
	}

	@Test
	void takesFileNamesFromUrls() {
		assertEquals("a.jar", Setup.fileNameOf(URI.create("https://example.com/x/a.jar?token=1")));
		assertEquals("download.jar", Setup.fileNameOf(URI.create("https://example.com/download")));
		assertThrows(SetupException.class, () -> Setup.fileNameOf(URI.create("https://example.com/")));
	}
}
