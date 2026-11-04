package dev.advik.mcdt.baritone;

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

class BaritoneIntegrationTest {
	@TempDir
	Path tempDir;

	private McdtEnvironment env;
	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private MockGameClient mockGameClient;
	private MockBaritoneService baritoneService;
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
				"1.13.1",
				Instant.now()
		);

		config = new McdtConfig();
		config.preferredPort = 0;
		config.permission = Permission.ACT;

		token = BearerToken.of("test-secret-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		mockGameClient = new MockGameClient();
		baritoneService = new MockBaritoneService();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), mockGameClient, baritoneService);
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

		if ("POST".equalsIgnoreCase(method)) {
			builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"));
		} else if ("PATCH".equalsIgnoreCase(method)) {
			builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"));
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
	void testPermissionGated() throws Exception {
		config.permission = Permission.READ;
		// GET works with READ
		HttpResponse<String> getResp = send("/v1/baritone", "GET", null);
		assertEquals(200, getResp.statusCode());

		// POST requires ACT
		HttpResponse<String> postResp = send("/v1/baritone/goto", "POST", "{\"x\":100,\"y\":64,\"z\":-200}");
		assertEquals(403, postResp.statusCode());
		assertTrue(postResp.body().contains("permission-denied"));
	}

	@Test
	void testGetStatusAndPath() throws Exception {
		HttpResponse<String> statusResp = send("/v1/baritone", "GET", null);
		assertEquals(200, statusResp.statusCode());
		JsonNode statusJson = Json.MAPPER.readTree(statusResp.body());
		assertTrue(statusJson.get("installed").asBoolean());
		assertEquals("CustomGoalProcess", statusJson.get("activeProcess").asText());
		assertEquals("GoalBlock", statusJson.get("goal").get("type").asText());

		HttpResponse<String> pathResp = send("/v1/baritone/path", "GET", null);
		assertEquals(200, pathResp.statusCode());
		JsonNode pathJson = Json.MAPPER.readTree(pathResp.body());
		assertTrue(pathJson.get("hasPath").asBoolean());
		assertTrue(pathJson.get("currentSegment").size() >= 3);
	}

	@Test
	void testGotoAndJobLifecycle() throws Exception {
		HttpResponse<String> gotoResp = send("/v1/baritone/goto", "POST", "{\"x\":100,\"y\":64,\"z\":-200}");
		assertEquals(202, gotoResp.statusCode());

		JsonNode gotoJson = Json.MAPPER.readTree(gotoResp.body());
		String jobId = gotoJson.get("jobId").asText();
		assertNotNull(jobId);
		assertEquals("goto", gotoJson.get("type").asText());
		assertEquals("started", gotoJson.get("status").asText());

		// Query job by ID
		HttpResponse<String> jobResp = send("/v1/baritone/jobs/" + jobId, "GET", null);
		assertEquals(200, jobResp.statusCode());
		JsonNode jobJson = Json.MAPPER.readTree(jobResp.body());
		assertEquals(jobId, jobJson.get("jobId").asText());
		assertEquals("goto", jobJson.get("type").asText());
	}

	@Test
	void testMineAndFollow() throws Exception {
		HttpResponse<String> mineResp = send("/v1/baritone/mine", "POST", "{\"blocks\":[\"minecraft:iron_ore\"],\"quantity\":10}");
		assertEquals(202, mineResp.statusCode());
		JsonNode mineJson = Json.MAPPER.readTree(mineResp.body());
		assertEquals("mine", mineJson.get("type").asText());

		HttpResponse<String> followResp = send("/v1/baritone/follow", "POST", "{\"name\":\"Alex\"}");
		assertEquals(202, followResp.statusCode());
		JsonNode followJson = Json.MAPPER.readTree(followResp.body());
		assertEquals("follow", followJson.get("type").asText());
	}

	@Test
	void testCancelProcesses() throws Exception {
		HttpResponse<String> cancelResp = send("/v1/baritone/cancel", "POST", null);
		assertEquals(200, cancelResp.statusCode());
		JsonNode json = Json.MAPPER.readTree(cancelResp.body());
		assertTrue(json.get("cancelled").asBoolean());
	}

	@Test
	void testSettingsGetAndPatch() throws Exception {
		HttpResponse<String> getResp = send("/v1/baritone/settings", "GET", null);
		assertEquals(200, getResp.statusCode());
		JsonNode settingsJson = Json.MAPPER.readTree(getResp.body());
		assertTrue(settingsJson.has("allowBreak"));
		assertTrue(settingsJson.get("allowBreak").get("value").asBoolean());

		HttpResponse<String> patchResp = send("/v1/baritone/settings", "PATCH", "{\"allowBreak\":false}");
		assertEquals(200, patchResp.statusCode());
		JsonNode patchJson = Json.MAPPER.readTree(patchResp.body());
		assertFalse(patchJson.get("allowBreak").asBoolean());
	}

	@Test
	void testCommandExecution() throws Exception {
		HttpResponse<String> cmdResp = send("/v1/baritone/command", "POST", "{\"command\":\"goto 100 64 -200\"}");
		assertEquals(200, cmdResp.statusCode());
		JsonNode cmdJson = Json.MAPPER.readTree(cmdResp.body());
		assertTrue(cmdJson.get("success").asBoolean());
		assertEquals("goto 100 64 -200", cmdJson.get("command").asText());
		assertEquals(1, baritoneService.executedCommands.size());
	}
}
