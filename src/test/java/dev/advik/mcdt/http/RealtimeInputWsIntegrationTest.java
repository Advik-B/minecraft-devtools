package dev.advik.mcdt.http;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.api.v1.dto.InputKeyDto;
import dev.advik.mcdt.api.v1.dto.InputMouseDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.json.Json;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeInputWsIntegrationTest {
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
		config.permission = Permission.ACT;

		token = BearerToken.of("test-input-token");
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
	void rejectsInputChannelWhenPermissionIsReadOnly() throws Exception {
		config.permission = Permission.READ; // Demote permission
		CountDownLatch closedLatch = new CountDownLatch(1);
		AtomicInteger closeCode = new AtomicInteger();

		httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer test-input-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/input/keys"), new WebSocket.Listener() {
					@Override
					public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
						closeCode.set(statusCode);
						closedLatch.countDown();
						return CompletableFuture.completedFuture(null);
					}

					@Override
					public void onError(WebSocket webSocket, Throwable error) {
						closedLatch.countDown();
					}
				});

		assertTrue(closedLatch.await(5, TimeUnit.SECONDS));
	}

	@Test
	void streamsKeyboardAndMouseInputsConcurrently() throws Exception {
		List<String> keyResponses = new CopyOnWriteArrayList<>();
		CountDownLatch keyConnectLatch = new CountDownLatch(1);
		CountDownLatch keyAckLatch = new CountDownLatch(1);

		WebSocket keyWs = httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer test-input-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/input/keys"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						String msg = data.toString();
						keyResponses.add(msg);
						if (msg.contains("\"status\":\"connected\"")) {
							keyConnectLatch.countDown();
						} else if (msg.contains("\"status\":\"ok\"")) {
							keyAckLatch.countDown();
						}
						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		assertTrue(keyConnectLatch.await(5, TimeUnit.SECONDS));

		CountDownLatch mouseConnectLatch = new CountDownLatch(1);
		WebSocket mouseWs = httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer test-input-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/input/mouse"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						if (data.toString().contains("\"status\":\"connected\"")) {
							mouseConnectLatch.countDown();
						}
						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		assertTrue(mouseConnectLatch.await(5, TimeUnit.SECONDS));

		// Send keyboard input
		InputKeyDto.Request keyReq = new InputKeyDto.Request("key.keyboard.w", null, null, "press", null, null, null);
		keyWs.sendText(Json.MAPPER.writeValueAsString(keyReq), true).get(5, TimeUnit.SECONDS);

		// Concurrently stream mouse movements on its own channel
		for (int i = 0; i < 5; i++) {
			InputMouseDto.Request mouseReq = new InputMouseDto.Request("move", null, null, null, null, 2.0, -1.0, null, null, null);
			mouseWs.sendText(Json.MAPPER.writeValueAsString(mouseReq), true).get(5, TimeUnit.SECONDS);
		}

		// Drain client tick queue in a loop to process queued inputs
		long deadline = System.currentTimeMillis() + 5000;
		while (!keyAckLatch.await(50, TimeUnit.MILLISECONDS) && System.currentTimeMillis() < deadline) {
			queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
		}

		assertTrue(keyResponses.stream().anyMatch(r -> r.contains("\"action\":\"press\"")));

		keyWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
		mouseWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
	}
}
