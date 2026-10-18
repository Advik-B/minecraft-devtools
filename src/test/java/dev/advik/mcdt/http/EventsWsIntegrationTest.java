package dev.advik.mcdt.http;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.events.SubscriptionRequest;
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

class EventsWsIntegrationTest {
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

		token = BearerToken.of("test-events-token");
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
	void rejectsUnauthorizedConnection() throws Exception {
		CountDownLatch closedLatch = new CountDownLatch(1);
		AtomicInteger closeCode = new AtomicInteger();

		httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer wrong-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/events"), new WebSocket.Listener() {
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
	void subscribesAndReceivesStreamedEvents() throws Exception {
		List<String> messages = new CopyOnWriteArrayList<>();
		CountDownLatch welcomeLatch = new CountDownLatch(1);
		CountDownLatch eventLatch = new CountDownLatch(2);

		WebSocket ws = httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer test-events-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/events"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						String str = data.toString();
						messages.add(str);
						if (str.contains("\"status\":\"connected\"")) {
							welcomeLatch.countDown();
						} else if (str.contains("\"topic\":\"chat\"") || str.contains("\"topic\":\"tick\"")) {
							eventLatch.countDown();
						}
						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		assertTrue(welcomeLatch.await(5, TimeUnit.SECONDS));

		// Subscribe to all topics
		SubscriptionRequest req = new SubscriptionRequest();
		req.action = "subscribe";
		req.topics = List.of("*");
		ws.sendText(Json.MAPPER.writeValueAsString(req), true).get(5, TimeUnit.SECONDS);

		Thread.sleep(100);

		// Publish events
		EventHub.getInstance().publish("chat", 100, Map.of("text", "hello live ws"));
		EventHub.getInstance().publish("tick", 101, 101);

		assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
		assertTrue(messages.stream().anyMatch(m -> m.contains("hello live ws")));
		assertTrue(messages.stream().anyMatch(m -> m.contains("\"topic\":\"tick\"")));

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
	}
}
