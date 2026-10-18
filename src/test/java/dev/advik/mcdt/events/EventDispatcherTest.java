package dev.advik.mcdt.events;

import dev.advik.mcdt.json.Json;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EventDispatcherTest {
	private Javalin app;
	private EventHub eventHub;
	private HttpClient httpClient;
	private int port;

	@BeforeEach
	void setUp() {
		EventHub.resetForTesting();
		eventHub = EventHub.getInstance();
		httpClient = HttpClient.newHttpClient();

		app = Javalin.create(cfg -> {
			cfg.routes.ws("/test/events", ws -> {
				ws.onConnect(ctx -> {
					EventSubscriber sub = eventHub.registerSubscriber(ctx, 32);
					SubscriptionRequest req = new SubscriptionRequest();
					req.action = "subscribe";
					req.topics = List.of("*");
					sub.updateSubscription(req);
				});
				ws.onClose(ctx -> eventHub.removeSubscriber(ctx));
			});
		}).start("127.0.0.1", 0);

		port = app.port();
	}

	@AfterEach
	void tearDown() {
		if (app != null) {
			app.stop();
		}
		EventHub.resetForTesting();
	}

	@Test
	void fansOutEventsToSubscriber() throws Exception {
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(2);

		WebSocket ws = httpClient.newWebSocketBuilder()
				.buildAsync(URI.create("ws://127.0.0.1:" + port + "/test/events"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						received.add(data.toString());
						latch.countDown();
						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		// Wait briefly for connection registration
		Thread.sleep(100);

		eventHub.publish("chat", 100, "hello world");
		eventHub.publish("tick", 101, 101);

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals(2, received.size());
		assertTrue(received.get(0).contains("\"topic\":\"chat\""));
		assertTrue(received.get(0).contains("hello world"));
		assertTrue(received.get(1).contains("\"topic\":\"tick\""));

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
	}

	@Test
	void filtersTopicsCorrectly() throws Exception {
		List<String> received = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(1);

		WebSocket ws = httpClient.newWebSocketBuilder()
				.buildAsync(URI.create("ws://127.0.0.1:" + port + "/test/events"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						received.add(data.toString());
						latch.countDown();
						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		Thread.sleep(100);

		// Update to only subscribe to "screen.open"
		EventSubscriber sub = eventHub.dispatcher().subscribers().get(0);
		SubscriptionRequest req = new SubscriptionRequest();
		req.action = "unsubscribe";
		req.topics = List.of("*");
		sub.updateSubscription(req);

		SubscriptionRequest subReq = new SubscriptionRequest();
		subReq.action = "subscribe";
		subReq.topics = List.of("screen.open");
		sub.updateSubscription(subReq);

		eventHub.publish("chat", 100, "ignore me");
		eventHub.publish("tick", 101, 101);
		eventHub.publish("screen.open", 102, "InventoryScreen");

		assertTrue(latch.await(5, TimeUnit.SECONDS));
		assertEquals(1, received.size());
		assertTrue(received.get(0).contains("\"topic\":\"screen.open\""));
		assertTrue(received.get(0).contains("InventoryScreen"));

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
	}
}

