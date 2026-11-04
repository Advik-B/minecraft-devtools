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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpSseTransportTest {
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
		config.mcpNotificationThrottleMs = 10; // Fast throttle for tests

		token = BearerToken.of("test-mcp-sse-token");
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

	@Test
	void testSseConnectionReceivesEndpointAndNotification() throws Exception {
		HttpRequest sseReq = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Accept", "text/event-stream")
				.GET()
				.build();

		CountDownLatch endpointLatch = new CountDownLatch(1);
		CountDownLatch notificationLatch = new CountDownLatch(1);
		AtomicReference<String> sessionIdRef = new AtomicReference<>();
		AtomicReference<String> receivedNotificationRef = new AtomicReference<>();

		Thread sseReader = new Thread(() -> {
			try {
				HttpResponse<java.io.InputStream> resp = httpClient.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
				assertEquals(200, resp.statusCode());
				assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

				BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()));
				String line;
				String currentEvent = null;

				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty()) {
						currentEvent = null;
						continue;
					}
					if (line.startsWith("event:")) {
						currentEvent = line.substring("event:".length()).trim();
					} else if (line.startsWith("data:")) {
						String data = line.substring("data:".length()).trim();
						if ("endpoint".equals(currentEvent)) {
							// data: /mcp?sessionId=...
							int idx = data.indexOf("sessionId=");
							if (idx != -1) {
								sessionIdRef.set(data.substring(idx + "sessionId=".length()));
							}
							endpointLatch.countDown();
						} else if ("message".equals(currentEvent)) {
							receivedNotificationRef.set(data);
							notificationLatch.countDown();
						}
					}
				}
			} catch (Exception ignored) {}
		});
		sseReader.setDaemon(true);
		sseReader.start();

		assertTrue(endpointLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for SSE endpoint event");
		String sessionId = sessionIdRef.get();
		assertNotNull(sessionId);

		// Subscribe to mcdt://player using the active sessionId
		HttpRequest subReq = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/mcp?sessionId=" + sessionId))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"mcdt://player\"}}"))
				.build();

		HttpResponse<String> subResp = httpClient.send(subReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, subResp.statusCode());

		// Trigger an event via EventHub
		EventHub.getInstance().publish("tick", Map.of("tick", 100L));

		assertTrue(notificationLatch.await(5, TimeUnit.SECONDS), "Timed out waiting for MCP resource update notification");
		String notifJson = receivedNotificationRef.get();
		assertNotNull(notifJson);
		assertTrue(notifJson.contains("notifications/resources/updated"));
		assertTrue(notifJson.contains("mcdt://player"));
	}

	@Test
	void testMcpStatusAndLogEndpoints() throws Exception {
		HttpRequest statusReq = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/mcp/status"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.GET()
				.build();

		HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, statusResp.statusCode());
		JsonNode statusJson = Json.MAPPER.readTree(statusResp.body());
		assertTrue(statusJson.get("enabled").asBoolean());
		assertEquals("2024-11-05", statusJson.get("protocolVersion").asText());

		HttpRequest logReq = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/mcp/log"))
				.header("Authorization", "Bearer " + token.value())
				.header("Host", "127.0.0.1:" + server.port())
				.GET()
				.build();

		HttpResponse<String> logResp = httpClient.send(logReq, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, logResp.statusCode());
		JsonNode logJson = Json.MAPPER.readTree(logResp.body());
		assertTrue(logJson.isArray());
	}
}
