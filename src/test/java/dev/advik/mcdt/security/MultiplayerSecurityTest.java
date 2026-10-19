package dev.advik.mcdt.security;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.baritone.MockBaritoneService;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.http.HttpServer;
import dev.advik.mcdt.http.RequestLogWriter;
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

class MultiplayerSecurityTest {
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
				"1.13.1",
				Instant.now()
		);

		config = new McdtConfig();
		config.preferredPort = 0;
		config.permission = Permission.REFLECT_WRITE; // Highest permission level

		token = BearerToken.of("test-secret-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		mockGameClient = new MockGameClient();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), mockGameClient, new MockBaritoneService());
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
		} else if ("PUT".equalsIgnoreCase(method)) {
			builder.PUT(HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"));
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
	void testMultiplayerWriteBlockedWhenUnlisted() throws Exception {
		mockGameClient.isSingleplayer = false;
		mockGameClient.isMultiplayer = true;
		mockGameClient.currentServer = "mc.hypixel.net";

		// Read routes still succeed
		HttpResponse<String> readResp = send("/v1/player", "GET", null);
		assertEquals(200, readResp.statusCode());

		// ACT action blocked
		HttpResponse<String> actResp = send("/v1/player/actions", "POST", "{\"action\":\"swing\"}");
		assertEquals(403, actResp.statusCode());
		assertTrue(actResp.body().contains("multiplayer-write-disabled"));
		assertTrue(actResp.body().contains("mc.hypixel.net"));

		// Baritone write blocked
		HttpResponse<String> baritoneResp = send("/v1/baritone/goto", "POST", "{\"x\":0,\"y\":64,\"z\":0}");
		assertEquals(403, baritoneResp.statusCode());
		assertTrue(baritoneResp.body().contains("multiplayer-write-disabled"));

		// REFLECT_WRITE field set blocked
		HttpResponse<String> fieldResp = send("/v1/objects/minecraft/fields/fps", "PUT", "{\"value\":60}");
		assertEquals(403, fieldResp.statusCode());
		assertTrue(fieldResp.body().contains("multiplayer-write-disabled"));

		// Raw packet send blocked
		HttpResponse<String> packetResp = send("/v1/packets", "POST", "{\"packet\":\"ServerboundSwingPacket\",\"data\":{\"hand\":\"MAIN_HAND\"}}");
		assertEquals(403, packetResp.statusCode());
		assertTrue(packetResp.body().contains("multiplayer-write-disabled"));
	}

	@Test
	void testMultiplayerWriteAllowedWhenListed() throws Exception {
		mockGameClient.isSingleplayer = false;
		mockGameClient.isMultiplayer = true;
		mockGameClient.currentServer = "mc.hypixel.net";
		config.allowedMultiplayerServers.add("mc.hypixel.net");

		// ACT action succeeds
		HttpResponse<String> actResp = send("/v1/player/actions", "POST", "{\"action\":\"swing\"}");
		assertEquals(200, actResp.statusCode());

		// Packet send succeeds
		HttpResponse<String> packetResp = send("/v1/packets", "POST", "{\"packet\":\"ServerboundSwingPacket\",\"data\":{\"hand\":\"MAIN_HAND\"}}");
		assertEquals(200, packetResp.statusCode());
	}
}
