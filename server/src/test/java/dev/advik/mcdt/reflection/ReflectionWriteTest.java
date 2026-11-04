package dev.advik.mcdt.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
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

class ReflectionWriteTest {
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
		config.permission = Permission.REFLECT_WRITE;

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

	private HttpResponse<String> send(String path, String method, String body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + path))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Authorization", "Bearer " + token.value())
				.header("Content-Type", "application/json");

		if ("PUT".equalsIgnoreCase(method)) {
			builder.PUT(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
		} else if ("POST".equalsIgnoreCase(method)) {
			builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
		} else {
			builder.GET();
		}

		CompletableFuture<HttpResponse<String>> responseFuture = httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
		while (!responseFuture.isDone()) {
			queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
			Thread.sleep(10);
		}
		return responseFuture.get();
	}

	@Test
	void testSetFieldPermissionGated() throws Exception {
		config.permission = Permission.READ;
		HttpResponse<String> resp1 = send("/v1/objects/minecraft/fields/fps", "PUT", "{\"value\":60}");
		assertEquals(403, resp1.statusCode());
		assertTrue(resp1.body().contains("permission-denied"));

		config.permission = Permission.ACT;
		HttpResponse<String> resp2 = send("/v1/objects/minecraft/fields/fps", "PUT", "{\"value\":60}");
		assertEquals(403, resp2.statusCode());
		assertTrue(resp2.body().contains("permission-denied"));
	}

	@Test
	void testSetFieldSuccess() throws Exception {
		assertEquals(144, mockGameClient.mockMinecraft.fps);

		HttpResponse<String> resp = send("/v1/objects/minecraft/fields/fps", "PUT", "{\"value\":60}");
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertTrue(json.get("success").asBoolean());
		assertEquals("fps", json.get("field").asText());
		assertEquals(60, json.get("value").asInt());

		// Verify field actually modified on target instance
		assertEquals(60, mockGameClient.mockMinecraft.fps);
	}

	@Test
	void testSetFieldFinalRejection() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/player/fields/name", "PUT", "{\"value\":\"NewName\"}");
		assertEquals(400, resp.statusCode());
		assertTrue(resp.body().contains("is final and cannot be modified"));
	}

	@Test
	void testSetFieldSensitiveRejection() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/player/fields/token", "PUT", "{\"value\":\"hacked\"}");
		assertEquals(403, resp.statusCode());
		assertTrue(resp.body().contains("reflection-denied"));
	}

	@Test
	void testSetFieldNotFound() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/minecraft/fields/nonexistentField", "PUT", "{\"value\":123}");
		assertEquals(404, resp.statusCode());
		assertTrue(resp.body().contains("field-not-found"));
	}

	@Test
	void testInvokePermissionGated() throws Exception {
		config.permission = Permission.ACT;
		HttpResponse<String> resp = send("/v1/objects/minecraft/invoke", "POST", "{\"method\":\"add\",\"args\":[1,2]}");
		assertEquals(403, resp.statusCode());
		assertTrue(resp.body().contains("permission-denied"));
	}

	@Test
	void testInvokeZeroArgMethod() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/minecraft/invoke", "POST", "{\"method\":\"getPlayer\",\"args\":[]}");
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertEquals("getPlayer", json.get("method").asText());
		assertTrue(json.has("result"));
		assertNotNull(json.get("result").get("$handle"));
	}

	@Test
	void testInvokeMultiArgMethodWithConversion() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/minecraft/invoke", "POST", "{\"method\":\"add\",\"args\":[15, 27]}");
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertEquals(42, json.get("result").asInt());

		HttpResponse<String> echoResp = send("/v1/objects/minecraft/invoke", "POST", "{\"method\":\"echo\",\"args\":[\"hello world\"]}");
		assertEquals(200, echoResp.statusCode());
		JsonNode echoJson = Json.MAPPER.readTree(echoResp.body());
		assertEquals("Echo: hello world", echoJson.get("result").asText());
	}

	@Test
	void testInvokeMethodNotFound() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/minecraft/invoke", "POST", "{\"method\":\"doesNotExist\",\"args\":[]}");
		assertEquals(404, resp.statusCode());
		assertTrue(resp.body().contains("method-not-found"));
	}

	@Test
	void testInvokeMethodThrowsException() throws Exception {
		HttpResponse<String> resp = send("/v1/objects/minecraft/invoke", "POST", "{\"method\":\"boom\",\"args\":[]}");
		assertEquals(400, resp.statusCode());
		assertTrue(resp.body().contains("invoke-failed"));
		assertTrue(resp.body().contains("Explosion occurred"));
	}
}
