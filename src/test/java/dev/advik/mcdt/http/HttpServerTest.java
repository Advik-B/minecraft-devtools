package dev.advik.mcdt.http;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.security.BearerToken;
import io.javalin.testtools.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerTest {
	@TempDir
	Path tempDir;

	private McdtEnvironment env;
	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private HttpServer server;
	private java.net.http.HttpClient httpClient;

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
		config.preferredPort = 0; // Use random free port for tests
		config.permission = Permission.READ;

		token = BearerToken.of("test-secret-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));

		server = new HttpServer(env, config, token, queue, logWriter, Map.of());
		httpClient = java.net.http.HttpClient.newHttpClient();
	}

	@AfterEach
	void tearDown() {
		server.close();
		queue.close();
		logWriter.close();
	}

	@Test
	void rootServesScalarDocsWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/"))
				.header("Host", "127.0.0.1:" + server.port())
				.GET()
				.build();

		HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("api-reference"));
		assertTrue(res.body().contains("/docs/scalar/standalone.js"));
	}

	@Test
	void openApiSpecServesJsonWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/openapi.json"))
				.header("Host", "127.0.0.1:" + server.port())
				.GET()
				.build();

		HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("Minecraft DevTools API"));
		assertTrue(res.body().contains("/v1/health"));
	}

	@Test
	void redocServesWithoutAuth() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/redoc"))
				.header("Host", "127.0.0.1:" + server.port())
				.GET()
				.build();

		HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, res.statusCode());
		assertTrue(res.body().contains("<redoc"));
	}

	@Test
	void healthEndpointRequiresBearerToken() throws Exception {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server.baseUrl() + "/v1/health"))
				.header("Host", "127.0.0.1:" + server.port())
				.GET()
				.build();

		HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
		assertEquals(401, res.statusCode());
		assertTrue(res.body().contains("unauthorized"));
	}

	@Test
	void healthEndpointDispatchesOntoClientThreadAndReturnsHealthResponse() throws Exception {
		clock.advance(); // tick 1

		// Launch the HTTP request in a background thread
		var asyncRes = httpClient.sendAsync(
				HttpRequest.newBuilder()
						.uri(URI.create(server.baseUrl() + "/v1/health"))
						.header("Host", "127.0.0.1:" + server.port())
						.header("Authorization", "Bearer test-secret-token")
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofString()
		);

		// Client tick drains the queue until the dispatched request is processed
		int drained = 0;
		long deadline = System.currentTimeMillis() + 5000;
		while (drained == 0 && System.currentTimeMillis() < deadline) {
			drained += queue.drain(TimeUnit.MILLISECONDS.toNanos(100));
			if (drained == 0) {
				Thread.sleep(10);
			}
		}
		assertEquals(1, drained);

		HttpResponse<String> res = asyncRes.get(5, TimeUnit.SECONDS);
		assertEquals(200, res.statusCode());
		assertEquals("1", res.headers().firstValue("X-MCDT-Tick").orElse(null));

		String body = res.body();
		assertTrue(body.contains("\"mcdtVersion\":\"0.1.0-SNAPSHOT\""));
		assertTrue(body.contains("\"gameVersion\":\"1.21.4\""));
		assertTrue(body.contains("\"baritone\":\"not-installed\""));
		assertTrue(body.contains("\"permission\":\"read\""));
		assertTrue(body.contains("\"depth\":0"));
	}
}
