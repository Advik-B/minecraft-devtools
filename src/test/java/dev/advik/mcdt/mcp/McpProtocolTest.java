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

import static org.junit.jupiter.api.Assertions.*;

class McpProtocolTest {
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

		token = BearerToken.of("test-mcp-token");
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

	private HttpResponse<String> postMcp(String jsonBody) throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();
		return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void testInitialize() throws Exception {
		String rpc = """
				{
				  "jsonrpc": "2.0",
				  "id": 1,
				  "method": "initialize",
				  "params": {
				    "protocolVersion": "2024-11-05",
				    "capabilities": {},
				    "clientInfo": { "name": "TestClient", "version": "1.0.0" }
				  }
				}
				""";

		HttpResponse<String> resp = postMcp(rpc);
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertEquals("2.0", json.get("jsonrpc").asText());
		assertEquals(1, json.get("id").asInt());
		assertTrue(json.get("error") == null || json.get("error").isNull());

		JsonNode result = json.get("result");
		assertNotNull(result);
		assertEquals("2024-11-05", result.get("protocolVersion").asText());
		assertEquals("mcdt", result.get("serverInfo").get("name").asText());
		assertTrue(result.get("capabilities").get("tools").get("listChanged").asBoolean());
		assertTrue(result.get("capabilities").get("resources").get("subscribe").asBoolean());
	}

	@Test
	void testPing() throws Exception {
		String rpc = """
				{
				  "jsonrpc": "2.0",
				  "id": 42,
				  "method": "ping"
				}
				""";

		HttpResponse<String> resp = postMcp(rpc);
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertEquals("2.0", json.get("jsonrpc").asText());
		assertEquals(42, json.get("id").asInt());
		assertNotNull(json.get("result"));
	}

	@Test
	void testNotificationInitialized() throws Exception {
		String rpc = """
				{
				  "jsonrpc": "2.0",
				  "method": "notifications/initialized"
				}
				""";

		HttpResponse<String> resp = postMcp(rpc);
		assertEquals(204, resp.statusCode());
	}

	@Test
	void testMethodNotFound() throws Exception {
		String rpc = """
				{
				  "jsonrpc": "2.0",
				  "id": 99,
				  "method": "unknown_custom_method"
				}
				""";

		HttpResponse<String> resp = postMcp(rpc);
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertEquals("2.0", json.get("jsonrpc").asText());
		assertEquals(99, json.get("id").asInt());
		assertNotNull(json.get("error"));
		assertEquals(-32601, json.get("error").get("code").asInt());
	}

	@Test
	void testParseError() throws Exception {
		HttpResponse<String> resp = postMcp("not valid json");
		assertEquals(400, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertNotNull(json.get("error"));
		assertEquals(-32700, json.get("error").get("code").asInt());
	}

	@Test
	void testBatchRequests() throws Exception {
		String rpc = """
				[
				  { "jsonrpc": "2.0", "id": 1, "method": "ping" },
				  { "jsonrpc": "2.0", "id": 2, "method": "ping" }
				]
				""";

		HttpResponse<String> resp = postMcp(rpc);
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertTrue(json.isArray());
		assertEquals(2, json.size());
		assertEquals(1, json.get(0).get("id").asInt());
		assertEquals(2, json.get(1).get("id").asInt());
	}
}
