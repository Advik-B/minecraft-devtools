package dev.advik.mcdt.mcp;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.baritone.MockBaritoneService;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.http.HttpServer;
import dev.advik.mcdt.http.RequestLogWriter;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.BearerToken;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpToolsTest {
	@TempDir
	Path tempDir;

	private McdtEnvironment env;
	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private MockGameClient gameClient;
	private MockBaritoneService baritoneService;
	private HttpServer server;
	private HttpClient httpClient;

	@BeforeEach
	void setUp() {
		EventHub.resetForTesting();

		env = new McdtEnvironment(
				"0.1.0-SNAPSHOT",
				"1.21.4",
				tempDir,
				tempDir.resolve("config"),
				tempDir.resolve("logs"),
				tempDir.resolve("home"),
				"TestPlayer",
				"1.13.1",
				Instant.now()
		);

		config = new McdtConfig();
		config.preferredPort = 0;
		config.permission = Permission.ACT;

		token = BearerToken.of("test-mcp-tools-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		gameClient = new MockGameClient();
		baritoneService = new MockBaritoneService();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), gameClient, baritoneService);
		httpClient = HttpClient.newHttpClient();
	}

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
		}
		EventHub.resetForTesting();
	}

	private void drainQueue() {
		clock.advance();
		queue.drain(TimeUnit.SECONDS.toNanos(1));
	}

	private JsonNode callMcp(String method, Object params) throws Exception {
		Map<String, Object> req = Map.of(
				"jsonrpc", "2.0",
				"id", 1,
				"method", method,
				"params", params != null ? params : Map.of()
		);
		String body = Json.MAPPER.writeValueAsString(req);

		Thread drainer = new Thread(() -> {
			for (int i = 0; i < 10; i++) {
				drainQueue();
				try {
					Thread.sleep(10);
				} catch (InterruptedException ignored) {}
			}
		});
		drainer.start();

		HttpRequest httpReq = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());
		return Json.MAPPER.readTree(resp.body());
	}

	@Test
	void testToolsListContainsExpectedTools() throws Exception {
		JsonNode res = callMcp("tools/list", Map.of());
		JsonNode tools = res.get("result").get("tools");
		assertTrue(tools.isArray());
		assertTrue(tools.size() >= 12);

		boolean hasGetState = false;
		boolean hasScreenshot = false;
		boolean hasLook = false;
		boolean hasMove = false;
		boolean hasBaritoneGoto = false;
		boolean hasGetPlayerSkin = false;
		boolean hasGetGameTextures = false;
		boolean hasViewBlocks = false;

		for (JsonNode t : tools) {
			String name = t.get("name").asText();
			if ("get_state".equals(name)) hasGetState = true;
			if ("screenshot".equals(name)) hasScreenshot = true;
			if ("look".equals(name)) hasLook = true;
			if ("move".equals(name)) hasMove = true;
			if ("baritone_goto".equals(name)) hasBaritoneGoto = true;
			if ("get_player_skin".equals(name)) hasGetPlayerSkin = true;
			if ("get_game_textures".equals(name)) hasGetGameTextures = true;
			if ("view_blocks".equals(name)) hasViewBlocks = true;
		}

		assertTrue(hasGetState);
		assertTrue(hasScreenshot);
		assertTrue(hasLook);
		assertTrue(hasMove);
		assertTrue(hasBaritoneGoto);
		assertTrue(hasGetPlayerSkin);
		assertTrue(hasGetGameTextures);
		assertTrue(hasViewBlocks);
	}

	@Test
	void testCallGetState() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "get_state", "arguments", Map.of()));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		JsonNode content = callResult.get("content").get(0);
		assertEquals("text", content.get("type").asText());
		assertTrue(content.get("text").asText().contains("survival"));
		assertTrue(content.get("text").asText().contains("overworld"));
	}

	@Test
	void testCallScreenshotReturnsImageContent() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "screenshot", "arguments", Map.of("scale", 0.2)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		JsonNode content = callResult.get("content").get(0);
		assertEquals("image", content.get("type").asText());
		assertEquals("image/png", content.get("mimeType").asText());
		String b64 = content.get("data").asText();
		assertNotNull(b64);
		byte[] decoded = Base64.getDecoder().decode(b64);
		assertTrue(decoded.length > 50);
	}

	@Test
	void testCallLook() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "look", "arguments", Map.of("yaw", 45.0, "pitch", -10.0)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		JsonNode content = callResult.get("content").get(0);
		assertTrue(content.get("text").asText().contains("45"));
	}

	@Test
	void testCallMove() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "move", "arguments", Map.of("forward", true, "sprint", true)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
	}

	@Test
	void testCallInteract() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "interact", "arguments", Map.of("action", "attack")));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
	}

	@Test
	void testCallInventoryClick() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "inventory_click", "arguments", Map.of("slot", 0, "mode", "pickup")));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
	}

	@Test
	void testCallScreenAction() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "screen_action", "arguments", Map.of("action", "click", "widgetId", 1)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
	}

	@Test
	void testCallChat() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "chat", "arguments", Map.of("message", "Hello Minecraft!")));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		assertTrue(callResult.get("content").get(0).get("text").asText().contains("Hello Minecraft!"));
	}

	@Test
	void testCallChatReadHistory() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "chat", "arguments", Map.of("read", true, "limit", 5)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
	}

	@Test
	void testCallRaycast() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "raycast", "arguments", Map.of("maxDistance", 10.0)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
	}

	@Test
	void testCallBaritoneTools() throws Exception {
		JsonNode gotoRes = callMcp("tools/call", Map.of("name", "baritone_goto", "arguments", Map.of("x", 100, "y", 64, "z", 200)));
		assertFalse(gotoRes.get("result").get("isError").asBoolean());
		assertTrue(gotoRes.get("result").get("content").get(0).get("text").asText().contains("job_"));

		JsonNode mineRes = callMcp("tools/call", Map.of("name", "baritone_mine", "arguments", Map.of("block", "minecraft:iron_ore")));
		assertFalse(mineRes.get("result").get("isError").asBoolean());

		JsonNode followRes = callMcp("tools/call", Map.of("name", "baritone_follow", "arguments", Map.of("entity", "Cow")));
		assertFalse(followRes.get("result").get("isError").asBoolean());

		JsonNode cancelRes = callMcp("tools/call", Map.of("name", "baritone_cancel", "arguments", Map.of()));
		assertFalse(cancelRes.get("result").get("isError").asBoolean());
	}

	@Test
	void testEscapeHatchRestCall() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "rest_call", "arguments", Map.of("method", "GET", "path", "/v1/client")));
		assertFalse(res.get("result").get("isError").asBoolean());
		assertTrue(res.get("result").get("content").get(0).get("text").asText().contains("singleplayer"));
	}

	@Test
	void testEscapeHatchInspect() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "inspect", "arguments", Map.of("handle", "options")));
		assertFalse(res.get("result").get("isError").asBoolean());
	}

	@Test
	void testCallGetPlayerSkin() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "get_player_skin", "arguments", Map.of()));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		assertTrue(callResult.get("content").isArray());
		assertEquals(2, callResult.get("content").size());
		assertEquals("text", callResult.get("content").get(0).get("type").asText());
		assertEquals("image", callResult.get("content").get(1).get("type").asText());
		assertEquals("image/png", callResult.get("content").get(1).get("mimeType").asText());
	}

	@Test
	void testCallGetGameTextures() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "get_game_textures", "arguments", Map.of("id", "blocks")));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		assertTrue(callResult.get("content").get(0).get("text").asText().contains("minecraft:block/stone"));
	}

	@Test
	void testCallGetGameTexturesWithEmbeddedImage() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "get_game_textures", "arguments", Map.of("id", "blocks", "embed_image", true)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		assertEquals(2, callResult.get("content").size());
		assertEquals("image", callResult.get("content").get(1).get("type").asText());
	}

	@Test
	void testCallViewBlocks() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "view_blocks", "arguments", Map.of("res", "128x72")));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		assertTrue(callResult.get("content").get(0).get("text").asText().contains("minecraft:stone"));
	}

	@Test
	void testCallViewBlocksSummary() throws Exception {
		JsonNode res = callMcp("tools/call", Map.of("name", "view_blocks", "arguments", Map.of("summaryOnly", true)));
		JsonNode callResult = res.get("result");
		assertFalse(callResult.get("isError").asBoolean());
		assertTrue(callResult.get("content").get(0).get("text").asText().contains("counts"));
	}
}
