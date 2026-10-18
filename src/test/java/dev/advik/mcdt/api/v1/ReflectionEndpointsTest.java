package dev.advik.mcdt.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionEndpointsTest {
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
		return send(path, "GET", null, "Bearer " + token.value());
	}

	private HttpResponse<String> delete(String path) throws Exception {
		return send(path, "DELETE", null, "Bearer " + token.value());
	}

	private HttpResponse<String> send(String path, String method, String body, String authHeader) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + path))
				.header("Host", "127.0.0.1:" + server.port());

		if (authHeader != null) {
			builder.header("Authorization", authHeader);
		}

		if ("DELETE".equalsIgnoreCase(method)) {
			builder.DELETE();
		} else {
			builder.GET();
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

		assertTrue(asyncRes.isDone(), "Request timed out on queue");
		return asyncRes.get();
	}

	@Test
	void testGetRoots() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/roots");
		assertEquals(200, resp.statusCode());

		JsonNode node = Json.MAPPER.readTree(resp.body());
		assertTrue(node.has("roots"));
		JsonNode roots = node.get("roots");
		assertTrue(roots.has("minecraft"));
		assertTrue(roots.has("player"));
		assertTrue(roots.has("level"));
		assertTrue(roots.has("screen"));
		assertTrue(roots.has("options"));
		assertTrue(roots.has("gameRenderer"));

		assertEquals("minecraft", roots.get("minecraft").get("$handle").asText());
	}

	@Test
	void testInspectMinecraftRoot() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/minecraft");
		assertEquals(200, resp.statusCode());

		JsonNode node = Json.MAPPER.readTree(resp.body());
		assertEquals("minecraft", node.get("$handle").asText());
		assertTrue(node.has("$class"));
		assertTrue(node.has("fields"));
		assertTrue(node.has("methods"));
	}

	@Test
	void testPathQuery() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/minecraft?path=player.inventory.items[0]");
		assertEquals(200, resp.statusCode());

		JsonNode node = Json.MAPPER.readTree(resp.body());
		assertEquals("player.inventory.items[0]", node.get("path").asText());
		assertEquals("minecraft:diamond_sword", node.get("value").asText());
	}

	@Test
	void testInspectField() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/minecraft/fields/player");
		assertEquals(200, resp.statusCode());

		JsonNode node = Json.MAPPER.readTree(resp.body());
		assertEquals("player", node.get("field").asText());
		assertTrue(node.has("declaringClass"));
		assertTrue(node.has("value"));
		assertTrue(node.get("value").has("$handle"));
	}

	@Test
	void testInspectClass() throws Exception {
		HttpResponse<String> resp = get("/v1/classes/java.lang.String");
		assertEquals(200, resp.statusCode());

		JsonNode node = Json.MAPPER.readTree(resp.body());
		assertEquals("java.lang.String", node.get("$class").asText());
		assertTrue(node.has("staticMethods"));
	}

	@Test
	void testDenylistBlocksDangerousClass() throws Exception {
		HttpResponse<String> resp = get("/v1/classes/java.lang.Runtime");
		assertEquals(403, resp.statusCode());

		JsonNode node = Json.MAPPER.readTree(resp.body());
		assertEquals("reflection-denied", node.get("type").asText());
	}

	@Test
	void testReleaseHandle() throws Exception {
		// First get the player field handle
		HttpResponse<String> fieldResp = get("/v1/objects/minecraft/fields/player");
		assertEquals(200, fieldResp.statusCode());

		JsonNode fieldNode = Json.MAPPER.readTree(fieldResp.body());
		String playerHandle = fieldNode.get("value").get("$handle").asText();
		assertNotNull(playerHandle);
		assertTrue(playerHandle.startsWith("h_"));

		// Release handle
		HttpResponse<String> delResp = delete("/v1/objects/" + playerHandle);
		assertEquals(200, delResp.statusCode());

		// Re-query released handle -> 404
		HttpResponse<String> queryResp = get("/v1/objects/" + playerHandle);
		assertEquals(404, queryResp.statusCode());
	}

	@Test
	void testCannotReleaseRootHandle() throws Exception {
		HttpResponse<String> delResp = delete("/v1/objects/minecraft");
		assertEquals(400, delResp.statusCode());
	}

	@Test
	void testReleaseNonExistentHandleReturns404() throws Exception {
		HttpResponse<String> delResp = delete("/v1/objects/h_00000000");
		assertEquals(404, delResp.statusCode());
	}

	@Test
	void testUnauthorizedWithoutToken() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/roots", "GET", null, null);
		assertEquals(401, resp.statusCode());
	}

	@Test
	void testInvalidDepthReturns400() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/minecraft?depth=-1");
		assertEquals(400, resp.statusCode());
		assertTrue(resp.body().contains("invalid-parameter"));
	}

	@Test
	void testDepthTwoInspect() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/minecraft?depth=2");
		assertEquals(200, resp.statusCode());
		JsonNode node = Json.MAPPER.readTree(resp.body());
		assertTrue(node.has("fields"));
	}

	@Test
	void testInvalidPathReturns400() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/minecraft?path=nonExistent.subField");
		assertEquals(400, resp.statusCode());
		assertTrue(resp.body().contains("path-evaluation-failed"));
	}

	@Test
	void testMissingFieldReturns404() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/minecraft/fields/nonExistentField");
		assertEquals(404, resp.statusCode());
		assertTrue(resp.body().contains("field-not-found"));
	}

	@Test
	void testInspectNonExistentHandleReturns404() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/h_99999999");
		assertEquals(404, resp.statusCode());
		assertTrue(resp.body().contains("handle-not-found"));
	}

	@Test
	void testInspectFieldOnNonExistentHandleReturns404() throws Exception {
		HttpResponse<String> resp = get("/v1/objects/h_99999999/fields/foo");
		assertEquals(404, resp.statusCode());
		assertTrue(resp.body().contains("handle-not-found"));
	}

	@Test
	void testNonExistentClassReturns404() throws Exception {
		HttpResponse<String> resp = get("/v1/classes/com.nonexistent.NoSuchClass");
		assertEquals(404, resp.statusCode());
		assertTrue(resp.body().contains("class-not-found"));
	}

	@Test
	void testNullRootReturns409() throws Exception {
		mockGameClient.hasPlayer = false;
		HttpResponse<String> resp = get("/v1/objects/player");
		assertEquals(409, resp.statusCode());
		assertTrue(resp.body().contains("no-player"));

		HttpResponse<String> fieldResp = get("/v1/objects/player/fields/name");
		assertEquals(409, fieldResp.statusCode());
		assertTrue(fieldResp.body().contains("no-player"));
	}
}
