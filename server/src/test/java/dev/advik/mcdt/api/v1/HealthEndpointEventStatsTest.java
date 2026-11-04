package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HealthEndpointEventStatsTest {
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

		token = BearerToken.of("test-health-token");
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
	void healthEndpointReportsDroppedEvents() throws Exception {
		var asyncRes = httpClient.sendAsync(
				HttpRequest.newBuilder()
						.uri(URI.create(server.baseUrl() + "/v1/health"))
						.header("Host", "127.0.0.1:" + server.port())
						.header("Authorization", "Bearer test-health-token")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofString()
		);

		long deadline = System.currentTimeMillis() + 5000;
		int drained = 0;
		while (drained == 0 && System.currentTimeMillis() < deadline) {
			drained += queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
			if (drained == 0) Thread.sleep(10);
		}

		HttpResponse<String> res = asyncRes.get(5, TimeUnit.SECONDS);
		assertEquals(200, res.statusCode());
		String body = res.body();
		assertTrue(body.contains("\"droppedEvents\":0"));
	}
}
