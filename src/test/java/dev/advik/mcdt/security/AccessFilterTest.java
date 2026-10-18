package dev.advik.mcdt.security;

import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.ProblemException;
import dev.advik.mcdt.json.Json;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccessFilterTest {
	private McdtConfig config;
	private BearerToken token;
	private RateLimiter rateLimiter;
	private AtomicInteger port;
	private Javalin app;

	@BeforeEach
	void setUp() {
		config = new McdtConfig();
		config.permission = Permission.READ;
		config.originAllowlist = List.of("http://allowed-dashboard.local");
		token = BearerToken.of("test-secret-token");
		rateLimiter = new RateLimiter(50);
		port = new AtomicInteger(25580);

		AccessFilter accessFilter = new AccessFilter(config, token, rateLimiter, port::get);

		app = Javalin.create(cfg -> {
			cfg.startup.showJavalinBanner = false;
			cfg.routes.before(accessFilter);
			cfg.routes.beforeMatched(accessFilter::checkPermission);

			cfg.routes.get("/public", ctx -> ctx.result("public-ok"));
			cfg.routes.get("/v1/read-endpoint", ctx -> ctx.result("read-ok"), McdtRole.READ);
			cfg.routes.post("/v1/act-endpoint", ctx -> ctx.result("act-ok"), McdtRole.ACT);

			cfg.routes.exception(ProblemException.class, (e, ctx) -> {
				e.headers().forEach(ctx::header);
				Problem problem = e.problem().withInstance(ctx.path());
				ctx.status(problem.status());
				ctx.contentType(Problem.CONTENT_TYPE);
				try {
					ctx.result(Json.MAPPER.writeValueAsString(problem));
				} catch (Exception ex) {
					ctx.result("{}");
				}
			});
		});
	}

	@Test
	void publicEndpointsDoNotRequireAuth() {
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/public", req -> req.header("Host", "127.0.0.1:" + server.port()));
			assertEquals(200, res.code());
			assertEquals("public-ok", res.body().string());
		});
	}

	@Test
	void rejectsForeignHost() {
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/public", req -> req.header("Host", "evil.attacker.com"));
			assertEquals(403, res.code());
			assertTrue(res.body().string().contains("bad-host"));
		});
	}

	@Test
	void rejectsDisallowedOrigin() {
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/public", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Origin", "http://malicious-website.com");
			});
			assertEquals(403, res.code());
			assertTrue(res.body().string().contains("origin-not-allowed"));
		});
	}

	@Test
	void allowsConfiguredOriginAndSelfOrigin() {
		JavalinTest.test(app, (server, client) -> {
			port.set(server.port());

			// Configured allowlist origin
			Response res1 = client.get("/public", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Origin", "http://allowed-dashboard.local");
			});
			assertEquals(200, res1.code());
			assertEquals(List.of("http://allowed-dashboard.local"), res1.headers().get("Access-Control-Allow-Origin"));

			// Self origin
			Response res2 = client.get("/public", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Origin", "http://127.0.0.1:" + server.port());
			});
			assertEquals(200, res2.code());
			assertEquals(List.of("http://127.0.0.1:" + server.port()), res2.headers().get("Access-Control-Allow-Origin"));
		});
	}

	@Test
	void rejectsMissingOrInvalidBearerTokenOnV1() {
		JavalinTest.test(app, (server, client) -> {
			// Missing
			Response res1 = client.get("/v1/read-endpoint", req -> req.header("Host", "127.0.0.1:" + server.port()));
			assertEquals(401, res1.code());
			assertTrue(res1.body().string().contains("unauthorized"));

			// Invalid
			Response res2 = client.get("/v1/read-endpoint", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Bearer invalid-token");
			});
			assertEquals(401, res2.code());
			assertTrue(res2.body().string().contains("unauthorized"));
		});
	}

	@Test
	void rejectsTokenInQueryParameter() {
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/v1/read-endpoint?token=test-secret-token", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Bearer test-secret-token");
			});
			assertEquals(401, res.code());
			assertTrue(res.body().string().contains("not in query parameters"));
		});
	}

	@Test
	void allowsValidBearerTokenOnAllowedPermission() {
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/v1/read-endpoint", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Bearer test-secret-token");
			});
			assertEquals(200, res.code());
			assertEquals("read-ok", res.body().string());
		});
	}

	@Test
	void deniesRouteWhenPermissionInsufficient() {
		// App is configured with Permission.READ, act-endpoint requires Permission.ACT
		JavalinTest.test(app, (server, client) -> {
			Response res = client.post("/v1/act-endpoint", "{}", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Bearer test-secret-token");
			});
			assertEquals(403, res.code());
			assertTrue(res.body().string().contains("permission-denied"));
		});
	}
}
