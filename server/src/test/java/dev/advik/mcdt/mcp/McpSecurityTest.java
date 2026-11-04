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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpSecurityTest {
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
		config.permission = Permission.READ; // READ only!

		token = BearerToken.of("test-mcp-sec-token");
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

	@Test
	void testUnauthorizedWhenTokenMissing() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
				.build();

		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode());
	}

	@Test
	void testUnauthorizedWhenTokenInvalid() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer invalid-token")
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
				.build();

		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, resp.statusCode());
	}

	@Test
	void testReadOnlyModeOmitsActTools() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
				.build();

		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		JsonNode tools = json.get("result").get("tools");
		for (JsonNode t : tools) {
			String name = t.get("name").asText();
			assertNotEquals("look", name);
			assertNotEquals("move", name);
			assertNotEquals("interact", name);
			assertNotEquals("baritone_goto", name);
		}
	}

	@Test
	void testCallingActToolInReadModeFailsWithPermissionDenied() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"look\",\"arguments\":{\"yaw\":0.0}}}"))
				.build();

		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		JsonNode result = json.get("result");
		assertTrue(result.get("isError").asBoolean());
		assertTrue(result.get("content").get(0).get("text").asText().contains("Permission denied"));
	}

	@Test
	void testMultiplayerSafetyDeniesActToolsOnUnapprovedServers() throws Exception {
		// Enable ACT permission
		config.permission = Permission.ACT;
		gameClient.isMultiplayer = true;
		gameClient.currentServer = "untrusted.server.net";

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"move\",\"arguments\":{\"forward\":true}}}"))
				.build();

		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		JsonNode result = json.get("result");
		assertTrue(result.get("isError").asBoolean());
		assertTrue(result.get("content").get(0).get("text").asText().contains("Multiplayer security denied write action"));

		// Whitelist the server
		config.allowedMultiplayerServers = List.of("untrusted.server.net");

		Thread drainer = new Thread(() -> {
			for (int i = 0; i < 5; i++) {
				drainQueue();
				try { Thread.sleep(10); } catch (InterruptedException ignored) {}
			}
		});
		drainer.start();

		HttpResponse<String> resp2 = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, resp2.statusCode());
		JsonNode json2 = Json.MAPPER.readTree(resp2.body());
		assertFalse(json2.get("result").get("isError").asBoolean());
	}

	@Test
	void testDisabledMcpReturnsServiceUnavailable() throws Exception {
		config.enableMcp = false;

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
				.build();

		HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(503, resp.statusCode());
	}
}
