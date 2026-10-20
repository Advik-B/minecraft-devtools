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
	void allowsConfiguredHost() {
		config.allowedHosts = List.of("mcdt.example.com");
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/public", req -> req.header("Host", "MCDT.example.com:443"));
			assertEquals(200, res.code());

			Response other = client.get("/public", req -> req.header("Host", "evil.attacker.com"));
			assertEquals(403, other.code());
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

	@Test
	void allowsIpv6LoopbackHost() {
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/public", req -> req.header("Host", "[::1]:" + server.port()));
			assertEquals(200, res.code());
		});
	}

	@Test
	void rejectsMissingHostHeader() {
		io.javalin.http.Context mockCtx = (io.javalin.http.Context) java.lang.reflect.Proxy.newProxyInstance(
				io.javalin.http.Context.class.getClassLoader(),
				new Class<?>[]{io.javalin.http.Context.class},
				(proxy, method, args) -> {
					if ("header".equals(method.getName()) && "Host".equals(args[0])) {
						return null;
					}
					if ("path".equals(method.getName())) {
						return "/public";
					}
					return null;
				}
		);
		AccessFilter filter = new AccessFilter(config, token, rateLimiter, port::get);
		ProblemException e = assertThrows(ProblemException.class, () -> filter.handle(mockCtx));
		assertEquals(403, e.problem().status());
		assertEquals("bad-host", e.problem().type());
	}

	@Test
	void rejectsMalformedAuthHeader() {
		JavalinTest.test(app, (server, client) -> {
			Response res = client.get("/v1/read-endpoint", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Basic user:pass");
			});
			assertEquals(401, res.code());
			assertTrue(res.body().string().contains("unauthorized"));
		});
	}

	@Test
	void rejectsPayloadTooLarge() {
		config.maxRequestBytes = 10;
		JavalinTest.test(app, (server, client) -> {
			Response res = client.post("/v1/act-endpoint", "{\"large_payload_field\":\"way_too_big_content\"}", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Bearer test-secret-token");
			});
			assertEquals(413, res.code());
			assertTrue(res.body().string().contains("payload-too-large"));
		});
	}

	@Test
	void enforcesRateLimitWhenPermitsExhausted() {
		RateLimiter strictLimiter = new RateLimiter(1);
		AccessFilter strictFilter = new AccessFilter(config, token, strictLimiter, port::get);
		Javalin strictApp = Javalin.create(cfg -> {
			cfg.startup.showJavalinBanner = false;
			cfg.routes.before(strictFilter);
			cfg.routes.get("/v1/read-endpoint", ctx -> ctx.result("read-ok"), McdtRole.READ);
			cfg.routes.exception(ProblemException.class, (e, ctx) -> {
				e.headers().forEach(ctx::header);
				ctx.status(e.problem().status()).result(e.problem().type());
			});
		});

		JavalinTest.test(strictApp, (server, client) -> {
			// Request 1 succeeds
			Response res1 = client.get("/v1/read-endpoint", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Bearer test-secret-token");
			});
			assertEquals(200, res1.code());

			// Request 2 in immediate succession exceeds rate limit
			Response res2 = client.get("/v1/read-endpoint", req -> {
				req.header("Host", "127.0.0.1:" + server.port());
				req.header("Authorization", "Bearer test-secret-token");
			});
			assertEquals(429, res2.code());
			assertTrue(res2.body().string().contains("rate-limited"));
			assertEquals("1", res2.headers().get("Retry-After").get(0));
		});
	}
}
