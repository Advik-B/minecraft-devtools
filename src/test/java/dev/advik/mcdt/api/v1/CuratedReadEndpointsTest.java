package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.http.HttpServer;
import dev.advik.mcdt.http.RequestLogWriter;
import dev.advik.mcdt.security.BearerToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CuratedReadEndpointsTest {
	@TempDir
	Path tempDir;

	private McdtEnvironment env;
	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private MockGameClient mockGameClient;
	private HttpServer server;
	private HttpClient httpClient;

	@BeforeEach
	void setUp() {
		env = new McdtEnvironment(
				"0.1.0-SNAPSHOT",
				"1.21.4",
				tempDir,
				tempDir.resolve("config"),
				tempDir.resolve("logs"),
				tempDir.resolve("home"),
				"TestPlayer",
				"not-installed",
				Instant.now()
		);

		config = new McdtConfig();
		config.preferredPort = 0;
		config.permission = Permission.READ;

		token = BearerToken.of("test-secret-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		mockGameClient = new MockGameClient();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), mockGameClient);
		httpClient = HttpClient.newHttpClient();
	}

	@AfterEach
	void tearDown() {
		server.close();
		queue.close();
		logWriter.close();
	}

	private HttpResponse<String> get(String path) throws Exception {
		return send(path, "Bearer " + token.value());
	}

	private HttpResponse<String> send(String path, String authHeader) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + path))
				.header("Host", "127.0.0.1:" + server.port())
				.GET();

		if (authHeader != null) {
			builder.header("Authorization", authHeader);
		}

		CompletableFuture<HttpResponse<String>> asyncRes = httpClient.sendAsync(
				builder.build(),
				HttpResponse.BodyHandlers.ofString()
		);

		long deadline = System.currentTimeMillis() + 5000;
		while (!asyncRes.isDone() && System.currentTimeMillis() < deadline) {
			queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
			if (!asyncRes.isDone()) {
				Thread.sleep(10);
			}
		}

		assertTrue(asyncRes.isDone(), "Request timed out waiting for queue processing");
		return asyncRes.get();
	}

	@Test
	void endpointsRequireAuthorization() throws Exception {
		String[] paths = {
				"/v1/client",
				"/v1/player",
				"/v1/player/inventory",
				"/v1/world",
				"/v1/world/blocks?from=0,64,0&to=1,65,1",
				"/v1/world/entities",
				"/v1/world/raycast",
				"/v1/screen",
				"/v1/registries",
				"/v1/registries/minecraft:item",
				"/v1/options",
				"/v1/screenshot"
		};

		for (String path : paths) {
			HttpResponse<String> res = send(path, null);
			assertEquals(401, res.statusCode(), "Expected 401 for " + path);
			assertTrue(res.body().contains("unauthorized"));
		}
	}

	@Test
	void getClientReturnsClientState() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/client");
		assertEquals(200, res.statusCode());
		assertNotNull(res.headers().firstValue("X-MCDT-Tick").orElse(null));
		assertTrue(res.body().contains("\"fps\":144"));
		assertTrue(res.body().contains("\"connectionState\":\"singleplayer\""));
		assertTrue(res.body().contains("\"guiScale\":2.0"));
	}

	@Test
	void getPlayerReturnsPlayerStateWhenLoaded() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/player");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"health\":20.0"));
		assertTrue(res.body().contains("\"gameMode\":\"survival\""));
		assertTrue(res.body().contains("\"minecraft:speed\""));
	}

	@Test
	void getPlayerReturns409WhenNoPlayer() throws Exception {
		mockGameClient.hasPlayer = false;
		clock.advance();
		HttpResponse<String> res = get("/v1/player");
		assertEquals(409, res.statusCode());
		assertTrue(res.body().contains("no-player"));
	}

	@Test
	void getInventoryReturnsSlots() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/player/inventory");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:diamond_sword"));
		assertTrue(res.body().contains("minecraft:iron_boots"));
	}

	@Test
	void getWorldReturnsWorldStateWhenLoaded() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/world");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"dimension\":\"minecraft:overworld\""));
		assertTrue(res.body().contains("\"difficulty\":\"normal\""));
		assertTrue(res.body().contains("\"loadedChunks\":256"));
	}

	@Test
	void getWorldReturns409WhenNoWorld() throws Exception {
		mockGameClient.hasWorld = false;
		clock.advance();
		HttpResponse<String> res = get("/v1/world");
		assertEquals(409, res.statusCode());
		assertTrue(res.body().contains("no-world"));
	}

	@Test
	void getBlocksValidatesVolumeAndReturnsPalette() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/world/blocks?from=0,64,0&to=2,64,2");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"volume\":9"));
		assertTrue(res.body().contains("minecraft:stone"));
	}

	@Test
	void getBlocksFailsWhenVolumeExceedsLimit() throws Exception {
		clock.advance();
		// 100 x 100 x 10 = 100,000 blocks (> 32,768 limit)
		HttpResponse<String> res = get("/v1/world/blocks?from=0,0,0&to=99,9,99");
		assertEquals(400, res.statusCode());
		assertTrue(res.body().contains("blocks-limit-exceeded"));
	}

	@Test
	void getBlocksFailsOnMalformedCoords() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/world/blocks?from=invalid&to=0,64,0");
		assertEquals(400, res.statusCode());
		assertTrue(res.body().contains("invalid-coordinates"));
	}

	@Test
	void getEntitiesReturnsFilteredList() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/world/entities?radius=16&type=minecraft:pig");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"count\":1"));
		assertTrue(res.body().contains("minecraft:pig"));
		assertTrue(res.body().contains("Piggy"));
	}

	@Test
	void getEntitiesRejectsInvalidRadius() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/world/entities?radius=250");
		assertEquals(400, res.statusCode());
		assertTrue(res.body().contains("invalid-parameter"));
	}

	@Test
	void getRaycastReturnsHit() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/world/raycast");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"hitType\":\"block\""));
		assertTrue(res.body().contains("minecraft:grass_block"));
	}

	@Test
	void getScreenReturnsOpenScreenDetails() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/screen");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"open\":true"));
		assertTrue(res.body().contains("InventoryScreen"));
		assertTrue(res.body().contains("Recipe Book"));
	}

	@Test
	void getScreenReturnsClosedWhenNoScreen() throws Exception {
		mockGameClient.isScreenOpen = false;
		clock.advance();
		HttpResponse<String> res = get("/v1/screen");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"open\":false"));
	}

	@Test
	void getRegistriesListsAvailableRegistries() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/registries");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"count\":2"));
		assertTrue(res.body().contains("minecraft:item"));
	}

	@Test
	void getRegistryEntriesPaginatesAndFilters() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/registries/minecraft:item?query=diamond&limit=1");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"total\":2"));
		assertTrue(res.body().contains("\"limit\":1"));
		assertTrue(res.body().contains("minecraft:diamond"));
	}

	@Test
	void getRegistryEntriesReturns404ForUnknownRegistry() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/registries/minecraft:unknown_registry");
		assertEquals(404, res.statusCode());
		assertTrue(res.body().contains("registry-not-found"));
	}

	@Test
	void getOptionsReturnsSettings() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/options");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"fov\":70"));
		assertTrue(res.body().contains("\"graphicsMode\":\"fancy\""));
		assertTrue(res.body().contains("\"key.forward\""));
	}

	@Test
	void getScreenshotReturnsPngBytes() throws Exception {
		clock.advance();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/screenshot?scale=0.5"))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Authorization", "Bearer " + token.value())
				.GET()
				.build();

		var asyncRes = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
		long deadline = System.currentTimeMillis() + 5000;
		while (!asyncRes.isDone() && System.currentTimeMillis() < deadline) {
			queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
			if (!asyncRes.isDone()) {
				Thread.sleep(10);
			}
		}

		assertTrue(asyncRes.isDone());
		HttpResponse<byte[]> res = asyncRes.get();
		assertEquals(200, res.statusCode());
		assertEquals("image/png", res.headers().firstValue("Content-Type").orElse(""));
		assertTrue(res.body().length > 0);
	}

	@Test
	void openApiSpecDocumentsAllM1Endpoints() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/openapi.json"))
				.header("Host", "127.0.0.1:" + server.port())
				.GET()
				.build();

		HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode());
		String body = res.body();

		String[] expectedRoutes = {
				"/v1/health",
				"/v1/client",
				"/v1/player",
				"/v1/player/inventory",
				"/v1/world",
				"/v1/world/blocks",
				"/v1/world/entities",
				"/v1/world/raycast",
				"/v1/screen",
				"/v1/registries",
				"/v1/registries/{registry}",
				"/v1/options",
				"/v1/screenshot"
		};

		for (String route : expectedRoutes) {
			assertTrue(body.contains(route), "OpenAPI schema must contain route: " + route);
		}
	}

	@Test
	void getBlocksFailsOnMissingParams() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/world/blocks");
		assertEquals(400, res.statusCode());
	}

	@Test
	void getScreenshotFailsOnInvalidScale() throws Exception {
		clock.advance();
		HttpResponse<String> res1 = get("/v1/screenshot?scale=0.01");
		assertEquals(400, res1.statusCode());
		assertTrue(res1.body().contains("invalid-parameter"));

		HttpResponse<String> res2 = get("/v1/screenshot?scale=not-a-number");
		assertEquals(400, res2.statusCode());
		assertTrue(res2.body().contains("invalid-parameter"));
	}

	@Test
	void getEntitiesAndRaycastFailWhenNoWorld() throws Exception {
		mockGameClient.hasWorld = false;
		clock.advance();

		HttpResponse<String> entRes = get("/v1/world/entities");
		assertEquals(409, entRes.statusCode());
		assertTrue(entRes.body().contains("no-world"));

		HttpResponse<String> rayRes = get("/v1/world/raycast");
		assertEquals(409, rayRes.statusCode());
		assertTrue(rayRes.body().contains("no-world"));
	}
}
