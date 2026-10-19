package dev.advik.mcdt.network;

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
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
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

class PacketsEndpointTest {
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

	private HttpResponse<String> postPacket(String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/packets"))
				.header("Host", "127.0.0.1:" + server.port())
				.header("Authorization", "Bearer " + token.value())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		CompletableFuture<HttpResponse<String>> responseFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		while (!responseFuture.isDone()) {
			queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
			Thread.sleep(10);
		}
		return responseFuture.get();
	}

	@Test
	void testPacketsPermissionGated() throws Exception {
		config.permission = Permission.ACT;
		HttpResponse<String> resp = postPacket("{\"packet\":\"ServerboundSwingPacket\",\"data\":{\"hand\":\"MAIN_HAND\"}}");
		assertEquals(403, resp.statusCode());
		assertTrue(resp.body().contains("permission-denied"));
	}

	@Test
	void testSendServerboundSwingPacket() throws Exception {
		HttpResponse<String> resp = postPacket("{\"packet\":\"ServerboundSwingPacket\",\"data\":{\"hand\":\"MAIN_HAND\"}}");
		assertEquals(200, resp.statusCode());

		JsonNode json = Json.MAPPER.readTree(resp.body());
		assertTrue(json.get("success").asBoolean());
		assertTrue(json.get("packet").asText().contains("ServerboundSwingPacket"));

		assertEquals(1, mockGameClient.sentPackets.size());
		Object packet = mockGameClient.sentPackets.get(0);
		assertTrue(packet instanceof ServerboundSwingPacket);
		assertEquals(InteractionHand.MAIN_HAND, ((ServerboundSwingPacket) packet).getHand());
	}

	@Test
	void testSendPacketOffHand() throws Exception {
		HttpResponse<String> resp = postPacket("{\"packet\":\"ServerboundSwingPacket\",\"data\":{\"hand\":\"off_hand\"}}");
		assertEquals(200, resp.statusCode());

		assertEquals(1, mockGameClient.sentPackets.size());
		ServerboundSwingPacket packet = (ServerboundSwingPacket) mockGameClient.sentPackets.get(0);
		assertEquals(InteractionHand.OFF_HAND, packet.getHand());
	}

	@Test
	void testPacketClassNotFound() throws Exception {
		HttpResponse<String> resp = postPacket("{\"packet\":\"ServerboundFakePacket\",\"data\":{}}");
		assertEquals(404, resp.statusCode());
		assertTrue(resp.body().contains("class-not-found"));
	}

	@Test
	void testPacketNoWorld() throws Exception {
		mockGameClient.hasWorld = false;
		HttpResponse<String> resp = postPacket("{\"packet\":\"ServerboundSwingPacket\",\"data\":{\"hand\":\"MAIN_HAND\"}}");
		assertEquals(409, resp.statusCode());
		assertTrue(resp.body().contains("no-world"));
	}
}
