package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.dto.WorldsDto;
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

	private HttpResponse<String> delete(String path) throws Exception {
		return send(path, "DELETE", null, "Bearer " + token.value());
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

	@Test
	void testInvalidInputActionsReturnBadRequest() throws Exception {
		// Invalid JSON payload
		HttpResponse<String> keyRes = post("/v1/input/keys", "invalid-json-body");
		assertEquals(400, keyRes.statusCode());

		// Invalid mouse payload
		HttpResponse<String> mouseRes = post("/v1/input/mouse", "invalid-json-body");
		assertEquals(400, mouseRes.statusCode());

		// Invalid chat payload (missing both message and command)
		HttpResponse<String> chatRes = post("/v1/chat", "{}");
		assertEquals(400, chatRes.statusCode());

		// Invalid player action
		HttpResponse<String> actRes = post("/v1/player/actions", "{\"action\":\"dance\"}");
		assertEquals(400, actRes.statusCode());

		// Hotbar slot out of bounds (< 0 or > 8)
		HttpResponse<String> hotbarRes = post("/v1/player/actions", "{\"action\":\"select_hotbar\",\"slot\":15}");
		assertEquals(400, hotbarRes.statusCode());
	}

	@Test
	void testUpdateCamera() throws Exception {
		clock.advance();
		HttpResponse<String> patchRes = patch("/v1/camera", "{\"perspective\":\"third_person_back\",\"fov\":90.0}");
		assertEquals(200, patchRes.statusCode());
		assertTrue(patchRes.body().contains("third_person_back"));
		assertTrue(patchRes.body().contains("90"));

		// Invalid perspective
		HttpResponse<String> badRes = post("/v1/camera", "{\"perspective\":\"invalid_view\"}");
		assertEquals(400, badRes.statusCode());
		assertTrue(badRes.body().contains("invalid-parameter"));
	}

	@Test
	void testUpdateWindow() throws Exception {
		clock.advance();
		HttpResponse<String> patchRes = patch("/v1/window", "{\"fullscreen\":true}");
		assertEquals(200, patchRes.statusCode());
		assertTrue(patchRes.body().contains("\"fullscreen\":true"));
	}

	@Test
	void testUpdateDebug() throws Exception {
		clock.advance();
		HttpResponse<String> patchRes = patch("/v1/debug", "{\"hitboxes\":true,\"chunkBorders\":true}");
		assertEquals(200, patchRes.statusCode());
		assertTrue(patchRes.body().contains("\"hitboxes\":true"));
		assertTrue(patchRes.body().contains("\"chunkBorders\":true"));
	}

	@Test
	void testUpdateKeybinds() throws Exception {
		clock.advance();
		HttpResponse<String> patchRes = patch("/v1/keybinds", "{\"rebinds\":{\"key.forward\":\"key.keyboard.up\"}}");
		assertEquals(200, patchRes.statusCode());
		assertTrue(patchRes.body().contains("keybinds"));

		HttpResponse<String> resetRes = post("/v1/keybinds", "{\"resetAll\":true}");
		assertEquals(200, resetRes.statusCode());
	}

	@Test
	void testUpdateResourcePacks() throws Exception {
		clock.advance();
		HttpResponse<String> patchRes = patch("/v1/resourcepacks", "{\"enabled\":[\"vanilla\"],\"reload\":true}");
		assertEquals(200, patchRes.statusCode());
		assertTrue(patchRes.body().contains("enabled"));
	}

	@Test
	void testPhase2RequiresActPermission() throws Exception {
		config.permission = Permission.READ;

		HttpResponse<String> camRes = patch("/v1/camera", "{\"perspective\":\"third_person_back\"}");
		assertEquals(403, camRes.statusCode());
		assertTrue(camRes.body().contains("permission-denied"));

		HttpResponse<String> winRes = patch("/v1/window", "{\"fullscreen\":true}");
		assertEquals(403, winRes.statusCode());
		assertTrue(winRes.body().contains("permission-denied"));

		HttpResponse<String> dbgRes = patch("/v1/debug", "{\"hitboxes\":true}");
		assertEquals(403, dbgRes.statusCode());
		assertTrue(dbgRes.body().contains("permission-denied"));

		HttpResponse<String> kbRes = patch("/v1/keybinds", "{\"resetAll\":true}");
		assertEquals(403, kbRes.statusCode());
		assertTrue(kbRes.body().contains("permission-denied"));

		HttpResponse<String> rpRes = patch("/v1/resourcepacks", "{\"reload\":true}");
		assertEquals(403, rpRes.statusCode());
		assertTrue(rpRes.body().contains("permission-denied"));

		HttpResponse<String> recipeRes = post("/v1/recipes/place", "{\"recipeId\":1}");
		assertEquals(403, recipeRes.statusCode());
		assertTrue(recipeRes.body().contains("permission-denied"));

		HttpResponse<String> sessRes = post("/v1/session/pause", "{\"paused\":true}");
		assertEquals(403, sessRes.statusCode());
		assertTrue(sessRes.body().contains("permission-denied"));

		HttpResponse<String> wldRes = post("/v1/worlds/New%20World/load", "");
		assertEquals(403, wldRes.statusCode());
		assertTrue(wldRes.body().contains("permission-denied"));

		HttpResponse<String> srvRes = post("/v1/servers/connect", "{\"address\":\"127.0.0.1:25565\"}");
		assertEquals(403, srvRes.statusCode());
		assertTrue(srvRes.body().contains("permission-denied"));
	}

	@Test
	void testSessionActions() throws Exception {
		clock.advance();
		HttpResponse<String> pauseRes = post("/v1/session/pause", "{\"paused\":true}");
		assertEquals(200, pauseRes.statusCode());
		assertTrue(pauseRes.body().contains("\"paused\":true"));

		clock.advance();
		HttpResponse<String> lanRes = post("/v1/session/lan", "{\"port\":25565}");
		assertEquals(200, lanRes.statusCode());
		assertTrue(lanRes.body().contains("\"port\":25565"));

		clock.advance();
		HttpResponse<String> discRes = post("/v1/session/disconnect", "");
		assertEquals(200, discRes.statusCode());
		assertTrue(discRes.body().contains("\"success\":true"));
	}

	@Test
	void testWorldActionsAndTwoStepDelete() throws Exception {
		clock.advance();
		HttpResponse<String> loadRes = post("/v1/worlds/New%20World/load", "");
		assertEquals(200, loadRes.statusCode());
		assertTrue(loadRes.body().contains("\"success\":true"));

		// Step 1: Request delete token
		HttpResponse<String> delReq = delete("/v1/worlds/WorldToDelete");
		assertEquals(202, delReq.statusCode());
		assertTrue(delReq.body().contains("confirmToken"));

		// Parse token
		String body = delReq.body();
		int tokenIdx = body.indexOf("\"confirmToken\":\"") + 16;
		String token = body.substring(tokenIdx, body.indexOf('"', tokenIdx));

		// Add world to mock so delete succeeds
		mockGameClient.mockWorldsList.add(new WorldsDto.WorldSummaryInfo("WorldToDelete", "Delete Me", 0, "survival", false, false, "1.21.4", false));

		// Step 2: Confirm delete
		clock.advance();
		HttpResponse<String> confRes = post("/v1/worlds/WorldToDelete/delete/confirm", "{\"confirmToken\":\"" + token + "\"}");
		assertEquals(200, confRes.statusCode());
		assertTrue(confRes.body().contains("\"success\":true"));

		// Token reuse fails
		HttpResponse<String> reuseRes = post("/v1/worlds/WorldToDelete/delete/confirm", "{\"confirmToken\":\"" + token + "\"}");
		assertEquals(409, reuseRes.statusCode());
		assertTrue(reuseRes.body().contains("quit-token-invalid"));
	}

	@Test
	void testServerActionsAndTwoStepDelete() throws Exception {
		clock.advance();
		// Add server
		HttpResponse<String> addRes = post("/v1/servers", "{\"name\":\"Hypixel\",\"address\":\"mc.hypixel.net\"}");
		assertEquals(200, addRes.statusCode());
		assertTrue(addRes.body().contains("Hypixel"));

		// Update server
		clock.advance();
		HttpResponse<String> updRes = patch("/v1/servers/0", "{\"name\":\"Updated Server\"}");
		assertEquals(200, updRes.statusCode());
		assertTrue(updRes.body().contains("Updated Server"));

		// Connect server
		clock.advance();
		HttpResponse<String> connRes = post("/v1/servers/connect", "{\"address\":\"127.0.0.1:25565\"}");
		assertEquals(200, connRes.statusCode());
		assertTrue(connRes.body().contains("\"success\":true"));

		// Step 1: Request delete token
		HttpResponse<String> delReq = delete("/v1/servers/0");
		assertEquals(202, delReq.statusCode());
		assertTrue(delReq.body().contains("confirmToken"));

		String body = delReq.body();
		int tokenIdx = body.indexOf("\"confirmToken\":\"") + 16;
		String token = body.substring(tokenIdx, body.indexOf('"', tokenIdx));

		// Step 2: Confirm delete
		clock.advance();
		HttpResponse<String> confRes = post("/v1/servers/0/delete/confirm", "{\"confirmToken\":\"" + token + "\"}");
		assertEquals(200, confRes.statusCode());
		assertTrue(confRes.body().contains("\"success\":true"));
	}

	@Test
	void testPlaceRecipe() throws Exception {
		clock.advance();
		HttpResponse<String> res = post("/v1/recipes/place", "{\"recipeId\":1,\"useMaxItems\":false}");
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("\"success\":true"));
		assertTrue(res.body().contains("\"recipeId\":1"));

		// When no container screen is open
		mockGameClient.isScreenOpen = false;
		HttpResponse<String> noScreenRes = post("/v1/recipes/place", "{\"recipeId\":1}");
		assertEquals(409, noScreenRes.statusCode());
		assertTrue(noScreenRes.body().contains("no-container"));
	}
}
