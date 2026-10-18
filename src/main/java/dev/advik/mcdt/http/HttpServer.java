package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.*;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.game.MinecraftGameClient;
import dev.advik.mcdt.config.ConfigStore;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.docs.DocsRoutes;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.AccessFilter;
import dev.advik.mcdt.security.BearerToken;
import dev.advik.mcdt.security.McdtRole;
import dev.advik.mcdt.security.RateLimiter;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;

import java.net.BindException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the embedded Javalin/Jetty server and route dispatching.
 */
public final class HttpServer implements AutoCloseable {
	private final McdtEnvironment env;
	private final McdtConfig config;
	private final BearerToken token;
	private final TaskQueue queue;
	private final RequestLogWriter logWriter;
	private final GameClient gameClient;
	private final Javalin app;
	private final AtomicInteger boundPort = new AtomicInteger();

	public HttpServer(
			McdtEnvironment env,
			McdtConfig config,
			BearerToken token,
			TaskQueue queue,
			RequestLogWriter logWriter,
			Map<String, String> envOverrides
	) {
		this(env, config, token, queue, logWriter, envOverrides, MinecraftGameClient.getInstance());
	}

	public HttpServer(
			McdtEnvironment env,
			McdtConfig config,
			BearerToken token,
			TaskQueue queue,
			RequestLogWriter logWriter,
			Map<String, String> envOverrides,
			GameClient gameClient
	) {
		this.env = env;
		this.config = config;
		this.token = token;
		this.queue = queue;
		this.logWriter = logWriter;
		this.gameClient = gameClient;

		int targetPort = ConfigStore.effectivePort(config, envOverrides);
		RateLimiter rateLimiter = new RateLimiter(config.rateLimitPerSecond);
		AccessFilter accessFilter = new AccessFilter(config, token, rateLimiter, boundPort::get);

		this.app = Javalin.create(cfg -> {
			cfg.startup.showJavalinBanner = false;
			cfg.http.maxRequestSize = config.maxRequestBytes;

			// Configure OpenAPI plugin
			cfg.registerPlugin(new OpenApiPlugin(openApi -> {
				openApi.withDocumentationPath("/openapi.json");
				openApi.withDefinitionConfiguration((version, schema) -> {
					schema.info(info -> info
							.title("Minecraft DevTools API")
							.version(env.mcdtVersion())
							.description("REST and WebSocket interface for the live Minecraft 1.21.4 client."));
					schema.withBearerAuth("BearerAuth");
				});
			}));

			// Configure ReDoc UI
			cfg.registerPlugin(new ReDocPlugin(reDoc -> {
				reDoc.withUiPath("/redoc");
				reDoc.withDocumentationPath("/openapi.json");
				reDoc.withTitle("MCDT ReDoc");
			}));

			// Global before filter for security and route role permission check
			cfg.routes.before(accessFilter);
			cfg.routes.beforeMatched(accessFilter::checkPermission);

			// Register docs routes
			cfg.routes.get("/", DocsRoutes.scalarHtml());
			cfg.routes.get("/docs/scalar/standalone.js", DocsRoutes.scalarJs());
			cfg.routes.get("/assets/mcdt/icon.png", DocsRoutes.icon());

			HealthHandler healthHandler = new HealthHandler(env, config, queue);
			cfg.routes.get("/v1/health", healthHandler, McdtRole.READ);

			cfg.routes.get("/v1/client", new ClientHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/player", new PlayerHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/player/inventory", new InventoryHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/world", new WorldHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/world/blocks", new BlocksHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/world/entities", new EntitiesHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/world/raycast", new RaycastHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/screen", new ScreenHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/registries", new RegistriesHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/registries/{registry}", new RegistryEntriesHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/options", new OptionsHandler(config, queue, gameClient), McdtRole.READ);
			cfg.routes.get("/v1/screenshot", new ScreenshotHandler(config, queue, gameClient), McdtRole.READ);

			// Exception handling -> RFC 9457 Problem JSON
			cfg.routes.exception(ProblemException.class, (e, ctx) -> {
				e.headers().forEach(ctx::header);
				Problem problem = e.problem().withInstance(ctx.path());
				ctx.status(problem.status());
				ctx.contentType(Problem.CONTENT_TYPE);
				ctx.result(serializeProblem(problem));
			});

			cfg.routes.exception(TimeoutException.class, (e, ctx) -> {
				Problem problem = Problems.timeout(config.requestTimeoutMs).problem().withInstance(ctx.path());
				ctx.status(problem.status());
				ctx.contentType(Problem.CONTENT_TYPE);
				ctx.result(serializeProblem(problem));
			});

			cfg.routes.exception(HttpResponseException.class, (e, ctx) -> {
				Problem problem = Problems.forStatus(e.getStatus(), e.getMessage()).withInstance(ctx.path());
				ctx.status(problem.status());
				ctx.contentType(Problem.CONTENT_TYPE);
				ctx.result(serializeProblem(problem));
			});

			cfg.routes.exception(Exception.class, (e, ctx) -> {
				Mcdt.LOGGER.error("Unhandled exception processing request to " + ctx.path(), e);
				Problem problem = Problems.internal("Internal server error").problem().withInstance(ctx.path());
				ctx.status(500);
				ctx.contentType(Problem.CONTENT_TYPE);
				ctx.result(serializeProblem(problem));
			});

			// Request log and tick header tracking
			cfg.routes.after(ctx -> {
				String durationStr = ctx.header("X-Response-Time");
				double durationMs = 0;
				if (durationStr != null) {
					try {
						durationMs = Double.parseDouble(durationStr);
					} catch (NumberFormatException ignored) {
					}
				}
				String tickHeader = ctx.header("X-MCDT-Tick");
				long tick = tickHeader != null ? Long.parseLong(tickHeader) : -1;
				String problemType = ctx.contentType() != null && ctx.contentType().contains(Problem.CONTENT_TYPE)
						? "problem"
						: null;

				logWriter.log(new RequestLogWriter.Entry(
						Instant.now(),
						ctx.method().name(),
						ctx.path(),
						ctx.status().getCode(),
						durationMs,
						tick,
						problemType,
						ctx.ip()
				));
			});
		});

		startWithPortFallback(targetPort);
	}

	private void startWithPortFallback(int targetPort) {
		String host = config.bindAddress;
		if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
			Mcdt.LOGGER.warn("MCDT is binding to {} instead of loopback (127.0.0.1)! The API is exposed to the network.", host);
		}

		try {
			app.start(host, targetPort);
			boundPort.set(app.port());
			Mcdt.LOGGER.info("MCDT HTTP server started at http://{}:{}/", host, app.port());
		} catch (Exception e) {
			if (targetPort != 0 && (e.getCause() instanceof BindException || e instanceof BindException)) {
				Mcdt.LOGGER.warn("Port {} is in use; trying a random free port...", targetPort);
				app.start(host, 0);
				boundPort.set(app.port());
				Mcdt.LOGGER.info("MCDT HTTP server started at http://{}:{}/", host, app.port());
			} else {
				throw new IllegalStateException("Failed to start MCDT HTTP server on " + host + ":" + targetPort, e);
			}
		}
	}

	private static String serializeProblem(Problem problem) {
		try {
			return Json.MAPPER.writeValueAsString(problem);
		} catch (Exception e) {
			return "{\"type\":\"internal\",\"title\":\"Internal Error\",\"status\":500}";
		}
	}

	public int port() {
		return boundPort.get();
	}

	public String baseUrl() {
		return "http://" + config.bindAddress + ":" + port();
	}

	@Override
	public void close() {
		try {
			app.stop();
		} catch (Exception e) {
			Mcdt.LOGGER.warn("Error stopping Javalin server: {}", e.getMessage());
		}
	}
}
