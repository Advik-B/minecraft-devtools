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

class CuratedActionEndpointsTest {
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
		config.permission = Permission.ACT;

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

	private HttpResponse<String> post(String path, String jsonBody) throws Exception {
		return send(path, "POST", jsonBody, "Bearer " + token.value());
	}

	private HttpResponse<String> patch(String path, String jsonBody) throws Exception {
		return send(path, "PATCH", jsonBody, "Bearer " + token.value());
	}

	private HttpResponse<String> send(String path, String method, String jsonBody, String authHeader) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + path))
				.header("Host", "127.0.0.1:" + server.port());

		if (authHeader != null) {
			builder.header("Authorization", authHeader);
		}

		if (jsonBody != null) {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
		} else if ("POST".equals(method)) {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString("{}"));
		} else if ("PATCH".equals(method)) {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString("{}"));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
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

		return asyncRes.get(1, TimeUnit.SECONDS);
	}

	@Test
	void testPermissionEnforcement() throws Exception {
		// When configured for READ permission only, ACT operations must fail with 403 permission-denied
		config.permission = Permission.READ;

		HttpResponse<String> postChat = post("/v1/chat", "{\"message\":\"test\"}");
		assertEquals(403, postChat.statusCode());
		assertTrue(postChat.body().contains("permission-denied"));

		HttpResponse<String> postKeys = post("/v1/input/keys", "{\"key\":\"w\"}");
		assertEquals(403, postKeys.statusCode());
		assertTrue(postKeys.body().contains("permission-denied"));

		// But GET /v1/chat requires only READ and must succeed
		HttpResponse<String> getChat = get("/v1/chat");
		assertEquals(200, getChat.statusCode());
		assertTrue(getChat.body().contains("Welcome to Minecraft DevTools"));
	}

	@Test
	void testInputKeys() throws Exception {
		HttpResponse<String> res = post("/v1/input/keys", "{\"key\":\"key.forward\",\"action\":\"press\"}");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"success\":true"));
		assertNotNull(mockGameClient.lastPressedKey);
		assertEquals("key.forward", mockGameClient.lastPressedKey.key());
		assertEquals("press", mockGameClient.lastPressedKey.action());
	}

	@Test
	void testInputMouse() throws Exception {
		HttpResponse<String> res = post("/v1/input/mouse", "{\"action\":\"click\",\"button\":\"left\",\"x\":150.0,\"y\":250.0}");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"success\":true"));
		assertNotNull(mockGameClient.lastMouseInput);
		assertEquals("click", mockGameClient.lastMouseInput.action());
		assertEquals("left", mockGameClient.lastMouseInput.button());
	}

	@Test
	void testChatAndHistory() throws Exception {
		HttpResponse<String> sendRes = post("/v1/chat", "{\"message\":\"Hello world\"}");
		assertEquals(200, sendRes.statusCode());
		assertTrue(sendRes.body().contains("\"sent\":true"));
		assertTrue(sendRes.body().contains("\"type\":\"chat\""));
		assertTrue(mockGameClient.sentMessages.contains("Hello world"));

		HttpResponse<String> cmdRes = post("/v1/chat", "{\"command\":\"time set day\"}");
		assertEquals(200, cmdRes.statusCode());
		assertTrue(cmdRes.body().contains("\"type\":\"command\""));

		HttpResponse<String> historyRes = get("/v1/chat?offset=0&limit=10");
		assertEquals(200, historyRes.statusCode());
		assertTrue(historyRes.body().contains("Hello world"));
		assertTrue(historyRes.body().contains("time set day"));
	}

	@Test
	void testPlayerLook() throws Exception {
		HttpResponse<String> res = post("/v1/player/look", "{\"yaw\":180.0,\"pitch\":-15.0}");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"yaw\":180.0"));
		assertTrue(res.body().contains("\"pitch\":-15.0"));
		assertEquals(180.0f, mockGameClient.currentYaw);
		assertEquals(-15.0f, mockGameClient.currentPitch);

		// Test target coordinates
		HttpResponse<String> targetRes = post("/v1/player/look", "{\"target\":[10.0, 64.0, 10.0]}");
		assertEquals(200, targetRes.statusCode());
		assertTrue(targetRes.body().contains("\"yaw\":45.0"));
	}

	@Test
	void testPlayerMove() throws Exception {
		HttpResponse<String> res = post("/v1/player/move", "{\"forward\":true,\"sprint\":true,\"ticks\":10}");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"forward\":true"));
		assertTrue(res.body().contains("\"sprint\":true"));
		assertTrue(res.body().contains("\"remainingTicks\":10"));
		assertNotNull(mockGameClient.lastMoveRequest);

		HttpResponse<String> stopRes = post("/v1/player/move", "{\"stop\":true}");
		assertEquals(200, stopRes.statusCode());
		assertTrue(stopRes.body().contains("\"forward\":false"));
		assertTrue(stopRes.body().contains("\"remainingTicks\":0"));
	}

	@Test
	void testPlayerActions() throws Exception {
		HttpResponse<String> atkRes = post("/v1/player/actions", "{\"action\":\"attack\"}");
		assertEquals(200, atkRes.statusCode());
		assertTrue(atkRes.body().contains("\"success\":true"));
		assertEquals("attack", mockGameClient.lastPlayerAction.action());

		HttpResponse<String> hotbarRes = post("/v1/player/actions", "{\"action\":\"select_hotbar\",\"slot\":4}");
		assertEquals(200, hotbarRes.statusCode());
		assertEquals("select_hotbar", mockGameClient.lastPlayerAction.action());
		assertEquals(4, mockGameClient.lastPlayerAction.slot());
	}

	@Test
	void testScreenActionsAndSlots() throws Exception {
		HttpResponse<String> clickRes = post("/v1/screen/actions", "{\"action\":\"click\",\"widgetIndex\":0}");
		assertEquals(200, clickRes.statusCode());
		assertTrue(clickRes.body().contains("\"success\":true"));

		HttpResponse<String> slotRes = post("/v1/screen/slots", "{\"slot\":5,\"mode\":\"pickup\",\"button\":0}");
		assertEquals(200, slotRes.statusCode());
		assertTrue(slotRes.body().contains("\"slot\":5"));
		assertTrue(slotRes.body().contains("\"mode\":\"pickup\""));

		// Close screen
		HttpResponse<String> closeRes = post("/v1/screen/actions", "{\"action\":\"close\"}");
		assertEquals(200, closeRes.statusCode());
		assertFalse(mockGameClient.isScreenOpen);

		// Operating when closed returns 409 no-screen
		HttpResponse<String> failRes = post("/v1/screen/actions", "{\"action\":\"click\",\"widgetIndex\":0}");
		assertEquals(409, failRes.statusCode());
		assertTrue(failRes.body().contains("no-screen"));
	}

	@Test
	void testOptionsPatch() throws Exception {
		HttpResponse<String> res = patch("/v1/options", "{\"fov\":95,\"renderDistance\":16}");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"fov\":95"));
		assertTrue(res.body().contains("\"renderDistance\":16"));
		assertEquals(95, mockGameClient.currentFov);
		assertEquals(16, mockGameClient.currentRenderDistance);
	}

	@Test
	void testQuitTwoStepConfirm() throws Exception {
		// Step 1: Request quit token
		HttpResponse<String> quitReq = post("/v1/session/quit", "{}");
		assertEquals(202, quitReq.statusCode());
		assertTrue(quitReq.body().contains("confirmToken"));
		assertTrue(quitReq.body().contains("expiresAt"));

		// Extract token
		String body = quitReq.body();
		int tokenIdx = body.indexOf("\"confirmToken\":\"") + 16;
		int endIdx = body.indexOf("\"", tokenIdx);
		String confirmToken = body.substring(tokenIdx, endIdx);
		assertFalse(confirmToken.isBlank());

		// Step 2: Confirm quit with matching token
		HttpResponse<String> confirmRes = post("/v1/session/quit/confirm", "{\"confirmToken\":\"" + confirmToken + "\"}");
		assertEquals(200, confirmRes.statusCode());
		assertTrue(confirmRes.body().contains("shutting_down"));

		// Step 3: Reusing or invalid token returns 409 quit-token-invalid
		HttpResponse<String> reusedRes = post("/v1/session/quit/confirm", "{\"confirmToken\":\"" + confirmToken + "\"}");
		assertEquals(409, reusedRes.statusCode());
		assertTrue(reusedRes.body().contains("quit-token-invalid"));

		HttpResponse<String> badRes = post("/v1/session/quit/confirm", "{\"confirmToken\":\"invalid-token\"}");
		assertEquals(409, badRes.statusCode());
		assertTrue(badRes.body().contains("quit-token-invalid"));
	}

	@Test
	void testQuitDisabled() throws Exception {
		config.allowQuit = false;

		HttpResponse<String> quitReq = post("/v1/session/quit", "{}");
		assertEquals(403, quitReq.statusCode());
		assertTrue(quitReq.body().contains("quit-disabled"));

		HttpResponse<String> confirmRes = post("/v1/session/quit/confirm", "{\"confirmToken\":\"any\"}");
		assertEquals(403, confirmRes.statusCode());
		assertTrue(confirmRes.body().contains("quit-disabled"));
	}
}
