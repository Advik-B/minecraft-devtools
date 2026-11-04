package dev.advik.mcdt.http;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.security.BearerToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InventoryAndPlayerStatusWsIntegrationTest {
	@TempDir
	Path tempDir;

	private McdtEnvironment env;
	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private MockGameClient gameClient;
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
				"not-installed",
				Instant.now()
		);

		config = new McdtConfig();
		config.preferredPort = 0;
		config.permission = Permission.READ;

		token = BearerToken.of("test-status-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		gameClient = new MockGameClient();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), gameClient);
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
	void streamsInventorySnapshotAndSlotUpdates() throws Exception {
		List<String> messages = new CopyOnWriteArrayList<>();
		CountDownLatch snapshotLatch = new CountDownLatch(1);
		CountDownLatch updateLatch = new CountDownLatch(1);

		WebSocket ws = httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer test-status-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/player/inventory"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						String msg = data.toString();
						messages.add(msg);
						if (msg.contains("\"type\":\"snapshot\"")) {
							snapshotLatch.countDown();
						} else if (msg.contains("\"type\":\"slot_update\"")) {
							updateLatch.countDown();
						}
						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		// Drain queue to process snapshot query
		Thread.sleep(50);
		queue.drain(TimeUnit.MILLISECONDS.toNanos(100));

		assertTrue(snapshotLatch.await(5, TimeUnit.SECONDS));

		// Broadcast a live slot update
		InventoryWsHandler.broadcastSlotUpdate(0, Map.of("id", "minecraft:netherite_sword", "count", 1), 105);

		assertTrue(updateLatch.await(5, TimeUnit.SECONDS));
		assertTrue(messages.stream().anyMatch(m -> m.contains("minecraft:netherite_sword")));

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
	}

	@Test
	void streamsPlayerStatusSnapshotAndDamageEvents() throws Exception {
		List<String> messages = new CopyOnWriteArrayList<>();
		CountDownLatch snapshotLatch = new CountDownLatch(1);
		CountDownLatch damageLatch = new CountDownLatch(1);

		WebSocket ws = httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer test-status-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/player/status"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						String msg = data.toString();
						messages.add(msg);
						if (msg.contains("\"type\":\"snapshot\"")) {
							snapshotLatch.countDown();
						} else if (msg.contains("\"type\":\"damage\"")) {
							damageLatch.countDown();
						}
						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		Thread.sleep(50);
		queue.drain(TimeUnit.MILLISECONDS.toNanos(100));

		assertTrue(snapshotLatch.await(5, TimeUnit.SECONDS));

		// Broadcast a live damage event (player hurt)
		PlayerStatusWsHandler.broadcastDamage(4.0f, 16.0f, "fall", 110);

		assertTrue(damageLatch.await(5, TimeUnit.SECONDS));
		assertTrue(messages.stream().anyMatch(m -> m.contains("\"amount\":4.0") && m.contains("\"health\":16.0")));

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
	}
}
