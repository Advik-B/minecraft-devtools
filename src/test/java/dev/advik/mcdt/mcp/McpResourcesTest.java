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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpResourcesTest {
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

		token = BearerToken.of("test-mcp-resources-token");
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
	void testResourcesList() throws Exception {
		JsonNode res = callMcp("resources/list", Map.of());
		JsonNode resources = res.get("result").get("resources");
		assertTrue(resources.isArray());
		assertEquals(5, resources.size());

		boolean hasPlayer = false;
		boolean hasInventory = false;
		boolean hasScreen = false;
		boolean hasWorld = false;
		boolean hasHud = false;

		for (JsonNode r : resources) {
			String uri = r.get("uri").asText();
			if ("mcdt://player".equals(uri)) hasPlayer = true;
			if ("mcdt://inventory".equals(uri)) hasInventory = true;
			if ("mcdt://screen".equals(uri)) hasScreen = true;
			if ("mcdt://world".equals(uri)) hasWorld = true;
			if ("mcdt://hud".equals(uri)) hasHud = true;
		}

		assertTrue(hasPlayer);
		assertTrue(hasInventory);
		assertTrue(hasScreen);
		assertTrue(hasWorld);
		assertTrue(hasHud);
	}

	@Test
	void testResourcesReadPlayer() throws Exception {
		JsonNode res = callMcp("resources/read", Map.of("uri", "mcdt://player"));
		JsonNode contents = res.get("result").get("contents").get(0);
		assertEquals("mcdt://player", contents.get("uri").asText());
		assertEquals("application/json", contents.get("mimeType").asText());
		assertTrue(contents.get("text").asText().contains("survival"));
	}

	@Test
	void testResourcesReadInventory() throws Exception {
		JsonNode res = callMcp("resources/read", Map.of("uri", "mcdt://inventory"));
		JsonNode contents = res.get("result").get("contents").get(0);
		assertEquals("mcdt://inventory", contents.get("uri").asText());
		assertTrue(contents.get("text").asText().contains("diamond_sword"));
	}

	@Test
	void testResourcesReadScreen() throws Exception {
		JsonNode res = callMcp("resources/read", Map.of("uri", "mcdt://screen"));
		JsonNode contents = res.get("result").get("contents").get(0);
		assertEquals("mcdt://screen", contents.get("uri").asText());
		assertTrue(contents.get("text").asText().contains("InventoryScreen"));
	}

	@Test
	void testResourcesReadWorld() throws Exception {
		JsonNode res = callMcp("resources/read", Map.of("uri", "mcdt://world"));
		JsonNode contents = res.get("result").get("contents").get(0);
		assertEquals("mcdt://world", contents.get("uri").asText());
		assertTrue(contents.get("text").asText().contains("overworld"));
	}

	@Test
	void testResourcesReadHud() throws Exception {
		JsonNode res = callMcp("resources/read", Map.of("uri", "mcdt://hud"));
		JsonNode contents = res.get("result").get("contents").get(0);
		assertEquals("mcdt://hud", contents.get("uri").asText());
		assertTrue(contents.get("text").asText().contains("recentChat"));
	}

	@Test
	void testResourcesSubscribeAndUnsubscribe() throws Exception {
		JsonNode subRes = callMcp("resources/subscribe", Map.of("uri", "mcdt://player"));
		assertNotNull(subRes.get("result"));

		JsonNode unsubRes = callMcp("resources/unsubscribe", Map.of("uri", "mcdt://player"));
		assertNotNull(unsubRes.get("result"));
	}
}
