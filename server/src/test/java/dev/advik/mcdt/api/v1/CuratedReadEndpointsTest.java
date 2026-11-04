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
				"/v1/screenshot",
				"/v1/player/skin",
				"/v1/player/skin/info",
				"/v1/textures/atlases",
				"/v1/textures/atlas",
				"/v1/textures/atlas/image"
		};

		for (String route : expectedRoutes) {
			assertTrue(body.contains(route), "OpenAPI schema must contain route: " + route);
		}
	}

	@Test
	void getPlayerSkinReturnsPngWithHeaders() throws Exception {
		clock.advance();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/player/skin"))
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
		assertEquals("wide", res.headers().firstValue("X-Skin-Model").orElse(""));
		assertEquals("DevPlayer", res.headers().firstValue("X-Player-Name").orElse(""));
		assertTrue(res.body().length > 0);
	}

	@Test
	void getPlayerSkinInfoReturnsJson() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/player/skin/info");
		assertEquals(200, res.statusCode());
		assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("application/json"));
		assertTrue(res.body().contains("\"playerName\":\"DevPlayer\""));
		assertTrue(res.body().contains("\"model\":\"wide\""));
	}

	@Test
	void getPlayerSkinFormatJsonParamReturnsJson() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/player/skin?format=json");
		assertEquals(200, res.statusCode());
		assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("application/json"));
		assertTrue(res.body().contains("\"playerName\":\"DevPlayer\""));
	}

	@Test
	void getPlayerSkinFailsWhenNoPlayer() throws Exception {
		mockGameClient.hasPlayer = false;
		clock.advance();
		HttpResponse<String> res = get("/v1/player/skin");
		assertEquals(409, res.statusCode());
		assertTrue(res.body().contains("no-player"));
	}

	@Test
	void getPlayerSkinFailsOnUnknownPlayer() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/player/skin?player=unknown_player");
		assertEquals(404, res.statusCode());
		assertTrue(res.body().contains("player-not-found"));
	}

	@Test
	void getTexturesAtlasesReturnsList() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/textures/atlases");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:textures/atlas/blocks.png"));
	}

	@Test
	void getTextureAtlasReturnsJsonAndUvs() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/textures/atlas?id=blocks");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"atlasId\":\"minecraft:textures/atlas/blocks.png\""));
		assertTrue(res.body().contains("minecraft:block/stone"));
		assertTrue(res.body().contains("\"u0\":0.0"));
		assertTrue(res.body().contains("\"u1\":0.015625"));
	}

	@Test
	void getTextureAtlasImageReturnsPng() throws Exception {
		clock.advance();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/textures/atlas/image?id=blocks"))
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
	void getTextureAtlasFailsOnNotFound() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/textures/atlas?id=non_existent");
		assertEquals(404, res.statusCode());
		assertTrue(res.body().contains("atlas-not-found"));
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

	@Test
	void getViewBlocksSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/view/blocks?res=128x72&grid=true");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("palette"));
		assertTrue(res.body().contains("minecraft:stone"));
		assertTrue(res.body().contains("labels"));
		assertTrue(res.body().contains("depth"));
		assertNotNull(res.headers().firstValue("X-MCDT-Tick").orElse(null));
	}

	@Test
	void getViewBlocksSummarySuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/view/blocks/summary");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("counts"));
		assertTrue(res.body().contains("nearest"));
		assertTrue(res.body().contains("crosshair"));
	}

	@Test
	void getViewBlocksFailsWhenDisabled() throws Exception {
		config.enableViewBlocks = false;
		clock.advance();
		HttpResponse<String> res = get("/v1/view/blocks");
		assertEquals(403, res.statusCode());
		assertTrue(res.body().contains("view-blocks-disabled"));
	}

	@Test
	void getHudSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/hud");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("Action bar test text"));
		assertTrue(res.body().contains("bossBars"));
		assertTrue(res.body().contains("scoreboard"));
		assertTrue(res.body().contains("tabList"));
	}

	@Test
	void postBatchSuccess() throws Exception {
		clock.advance();
		String batchReq = """
				{
				  "requests": [
				    { "id": "p", "path": "/v1/player" },
				    { "id": "w", "path": "/v1/world" },
				    { "id": "h", "path": "/v1/hud" }
				  ]
				}
				""";

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/batch"))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Authorization", "Bearer " + token.value())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(batchReq))
				.build();

		var asyncRes = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		long deadline = System.currentTimeMillis() + 5000;
		while (!asyncRes.isDone() && System.currentTimeMillis() < deadline) {
			queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
			if (!asyncRes.isDone()) Thread.sleep(10);
		}

		assertTrue(asyncRes.isDone());
		HttpResponse<String> res = asyncRes.get();
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"p\":{\"status\":200"));
		assertTrue(res.body().contains("\"w\":{\"status\":200"));
		assertTrue(res.body().contains("\"h\":{\"status\":200"));
	}

	@Test
	void postBatchRejectsWrites() throws Exception {
		clock.advance();
		String batchReq = """
				{
				  "requests": [
				    { "id": "p", "path": "/v1/player" },
				    { "id": "act", "path": "/v1/input/keys" }
				  ]
				}
				""";

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/batch"))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Authorization", "Bearer " + token.value())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(batchReq))
				.build();

		HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(400, res.statusCode());
		assertTrue(res.body().contains("only permits read operations"));
	}

	@Test
	void postBatchEnforcesSizeLimit() throws Exception {
		clock.advance();
		StringBuilder sb = new StringBuilder("{\"requests\":[");
		for (int i = 0; i < 33; i++) {
			if (i > 0) sb.append(",");
			sb.append("{\"id\":\"req").append(i).append("\",\"path\":\"/v1/player\"}");
		}
		sb.append("]}");

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/batch"))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Authorization", "Bearer " + token.value())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
				.build();

		HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(400, res.statusCode());
		assertTrue(res.body().contains("batch-limit-exceeded"));
	}

	@Test
	void getCameraSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/camera");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("first_person"));
		assertTrue(res.body().contains("fov"));
		assertTrue(res.body().contains("eyePosition"));
	}

	@Test
	void getWindowSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/window");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("width"));
		assertTrue(res.body().contains("height"));
		assertTrue(res.body().contains("guiScale"));
	}

	@Test
	void getDebugSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/debug");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("f3Left"));
		assertTrue(res.body().contains("f3Right"));
		assertTrue(res.body().contains("hitboxes"));
		assertTrue(res.body().contains("chunkBorders"));
	}

	@Test
	void getKeybindsSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/keybinds");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("key.forward"));
		assertTrue(res.body().contains("boundKey"));
	}

	@Test
	void getResourcePacksSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/resourcepacks");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("available"));
		assertTrue(res.body().contains("enabled"));
		assertTrue(res.body().contains("vanilla"));
	}

	@Test
	void getStatsSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/stats");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:jump"));
		assertTrue(res.body().contains("mined"));
		assertTrue(res.body().contains("minecraft:iron_ore"));
	}

	@Test
	void getStatsWithCategory() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/stats?category=mined");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:iron_ore"));
	}

	@Test
	void getStatsInvalidCategory() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/stats?category=invalid_category");
		assertEquals(400, res.statusCode());
		assertTrue(res.body().contains("invalid-parameter"));
	}

	@Test
	void getAdvancementsSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/advancements");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:story/root"));
		assertTrue(res.body().contains("total"));
		assertTrue(res.body().contains("completed"));
	}

	@Test
	void getAdvancementsFilter() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/advancements?done=true");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:story/root"));
		assertFalse(res.body().contains("minecraft:story/mine_stone"));
	}

	@Test
	void getRecipesSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/recipes");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:iron_pickaxe"));
		assertTrue(res.body().contains("crafting"));
	}

	@Test
	void getRecipesFilter() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/recipes?category=crafting");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("minecraft:iron_pickaxe"));
		assertFalse(res.body().contains("minecraft:raw_iron"));
	}

	@Test
	void getSessionSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/session");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("state"));
		assertTrue(res.body().contains("paused"));
	}

	@Test
	void getWorldsSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/worlds");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("New World"));
		assertTrue(res.body().contains("survival"));
	}

	@Test
	void getServersSuccess() throws Exception {
		clock.advance();
		HttpResponse<String> res = get("/v1/servers");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("Local Server"));
		assertTrue(res.body().contains("127.0.0.1:25565"));
	}
}
