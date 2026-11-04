package dev.advik.mcdt.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.http.HttpServer;
import dev.advik.mcdt.http.RequestLogWriter;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.BearerToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CapabilitiesTest {
	@TempDir
	Path tempDir;

	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private MockGameClient gameClient;
	private HttpServer server;
	private HttpClient httpClient;
	private Thread drainer;
	private volatile boolean draining = true;

	@BeforeEach
	void setUp() {
		EventHub.resetForTesting();
		McdtEnvironment env = new McdtEnvironment(
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
		config.permission = Permission.ACT;

		token = BearerToken.of("test-secret-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		gameClient = new MockGameClient();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), gameClient);
		httpClient = HttpClient.newHttpClient();

		drainer = new Thread(() -> {
			while (draining) {
				clock.advance();
				queue.drain(TimeUnit.MILLISECONDS.toNanos(50));
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "mcdt-test-client-thread");
		drainer.start();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		draining = false;
		drainer.join(1000);
		server.close();
		queue.close();
		logWriter.close();
		EventHub.resetForTesting();
	}

	private HttpResponse<String> send(String method, String path, String body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + path))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Authorization", "Bearer " + token.value())
				.header("Content-Type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode mcp(String method, Object params) throws Exception {
		String body = Json.MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
		HttpResponse<String> resp = send("POST", "/mcp", body);
		assertEquals(200, resp.statusCode(), resp.body());
		return Json.MAPPER.readTree(resp.body()).get("result");
	}

	private static List<String> textList(JsonNode array) {
		List<String> out = new ArrayList<>();
		array.forEach(n -> out.add(n.asText()));
		return out;
	}

	@Test
	void healthListsSortedCapabilities() throws Exception {
		HttpResponse<String> resp = send("GET", "/v1/health", null);
		assertEquals(200, resp.statusCode(), resp.body());
		List<String> caps = textList(Json.MAPPER.readTree(resp.body()).get("capabilities"));

		assertTrue(caps.contains("textures"));
		assertTrue(caps.contains("block-viewer"));
		assertFalse(caps.contains("baritone"), "Baritone is not installed in this test");
		assertEquals(caps.stream().sorted().toList(), caps);
	}

	@Test
	void missingCapabilityReturnsFeatureUnavailable() throws Exception {
		gameClient.capabilities.remove(Capability.TEXTURES);

		HttpResponse<String> resp = send("GET", "/v1/textures/atlases", null);
		assertEquals(501, resp.statusCode());
		JsonNode problem = Json.MAPPER.readTree(resp.body());
		assertEquals("feature-unavailable", problem.get("type").asText());
		assertTrue(problem.get("detail").asText().contains("textures"));

		JsonNode health = Json.MAPPER.readTree(send("GET", "/v1/health", null).body());
		assertFalse(textList(health.get("capabilities")).contains("textures"));

		// Other routes are unaffected.
		assertEquals(200, send("GET", "/v1/player", null).statusCode());
	}

	@Test
	void batchSubRequestHonoursCapabilities() throws Exception {
		gameClient.capabilities.remove(Capability.STATS);

		String body = "{\"requests\":[{\"id\":\"s\",\"path\":\"/v1/stats\"},{\"id\":\"p\",\"path\":\"/v1/player\"}]}";
		HttpResponse<String> resp = send("POST", "/v1/batch", body);
		assertEquals(200, resp.statusCode(), resp.body());
		JsonNode results = Json.MAPPER.readTree(resp.body()).get("results");
		assertEquals(501, results.get("s").get("status").asInt());
		assertEquals("feature-unavailable", results.get("s").get("problem").get("type").asText());
		assertEquals(200, results.get("p").get("status").asInt());
	}

	@Test
	void mcpHidesAndRefusesToolsWithoutCapability() throws Exception {
		assertTrue(toolNames().contains("get_game_textures"));

		gameClient.capabilities.remove(Capability.TEXTURES);
		assertFalse(toolNames().contains("get_game_textures"));

		JsonNode call = mcp("tools/call", Map.of("name", "get_game_textures", "arguments", Map.of()));
		assertTrue(call.get("isError").asBoolean());
		assertTrue(call.get("content").get(0).get("text").asText().contains("textures"));
	}

	@Test
	void webSocketUpgradeRefusedWithoutCapability() {
		gameClient.capabilities.remove(Capability.WORLD_STREAM);

		ExecutionException e = assertThrows(ExecutionException.class, () -> httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/world/stream"), new WebSocket.Listener() {})
				.get(5, TimeUnit.SECONDS));
		WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
		assertEquals(501, handshake.getResponse().statusCode());
	}

	private List<String> toolNames() throws Exception {
		List<String> names = new ArrayList<>();
		mcp("tools/list", Map.of()).get("tools").forEach(t -> names.add(t.get("name").asText()));
		return names;
	}
}
