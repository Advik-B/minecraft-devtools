package dev.advik.mcdt.baritone;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
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

class BaritoneNotInstalledTest {
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
		config.permission = Permission.REFLECT_WRITE; // full permissions

		token = BearerToken.of("test-secret-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		mockGameClient = new MockGameClient();

		// Notice: passing UnavailableBaritoneService
		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), mockGameClient, new UnavailableBaritoneService());
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
		} else if ("PATCH".equalsIgnoreCase(method)) {
			builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body != null ? body : "{}"));
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
	void testHealthReportsNotInstalled() throws Exception {
		HttpResponse<String> resp = send("/v1/health", "GET", null);
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains("\"baritone\":\"not-installed\""));
	}

	@Test
	void testAllBaritoneRoutesReturn501() throws Exception {
		String[] getRoutes = {
				"/v1/baritone",
				"/v1/baritone/path",
				"/v1/baritone/jobs/job_12345",
				"/v1/baritone/settings"
		};

		for (String route : getRoutes) {
			HttpResponse<String> resp = send(route, "GET", null);
			assertEquals(501, resp.statusCode(), "Route " + route + " should return 501");
			assertTrue(resp.body().contains("baritone-not-installed"));
			assertTrue(resp.body().contains("baritone-api-fabric-1.13.1.jar"));
		}

		String[] postRoutes = {
				"/v1/baritone/goto",
				"/v1/baritone/mine",
				"/v1/baritone/follow",
				"/v1/baritone/explore",
				"/v1/baritone/farm",
				"/v1/baritone/build",
				"/v1/baritone/cancel",
				"/v1/baritone/command"
		};

		for (String route : postRoutes) {
			HttpResponse<String> resp = send(route, "POST", "{}");
			assertEquals(501, resp.statusCode(), "Route " + route + " should return 501");
			assertTrue(resp.body().contains("baritone-not-installed"));
		}

		HttpResponse<String> patchResp = send("/v1/baritone/settings", "PATCH", "{}");
		assertEquals(501, patchResp.statusCode());
		assertTrue(patchResp.body().contains("baritone-not-installed"));
	}
}
