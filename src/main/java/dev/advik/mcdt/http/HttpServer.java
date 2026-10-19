package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.*;
import dev.advik.mcdt.reflection.*;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.game.MinecraftGameClient;
import dev.advik.mcdt.baritone.BaritoneService;
import dev.advik.mcdt.baritone.UnavailableBaritoneService;
import dev.advik.mcdt.config.ConfigStore;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.docs.DocsRoutes;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.AccessFilter;
import dev.advik.mcdt.security.BearerToken;
import dev.advik.mcdt.security.McdtRole;
import dev.advik.mcdt.security.RateLimiter;
import dev.advik.mcdt.security.ConfirmManager;
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
	private final BaritoneService baritoneService;
	private final dev.advik.mcdt.mcp.McpSessionManager mcpSessionManager;
	private final Javalin app;
	private final AtomicInteger boundPort = new AtomicInteger();
	private volatile Runnable onQuitCallback = () -> {};

	public HttpServer(
			McdtEnvironment env,
			McdtConfig config,
			BearerToken token,
			TaskQueue queue,
			RequestLogWriter logWriter,
			Map<String, String> envOverrides
	) {
		this(env, config, token, queue, logWriter, envOverrides, MinecraftGameClient.getInstance(), new UnavailableBaritoneService());
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
		this(env, config, token, queue, logWriter, envOverrides, gameClient, new UnavailableBaritoneService());
	}

	public HttpServer(
			McdtEnvironment env,
			McdtConfig config,
			BearerToken token,
			TaskQueue queue,
			RequestLogWriter logWriter,
			Map<String, String> envOverrides,
			GameClient gameClient,
			BaritoneService baritoneService
	) {
		this.env = env;
		this.config = config;
		this.token = token;
		this.queue = queue;
		this.logWriter = logWriter;
		this.gameClient = gameClient;
		this.baritoneService = baritoneService != null ? baritoneService : new UnavailableBaritoneService();
		this.mcpSessionManager = new dev.advik.mcdt.mcp.McpSessionManager(config.mcpNotificationThrottleMs);

		int targetPort = ConfigStore.effectivePort(config, envOverrides);
		RateLimiter rateLimiter = new RateLimiter(config.rateLimitPerSecond);
		AccessFilter accessFilter = new AccessFilter(config, token, rateLimiter, boundPort::get, gameClient);

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
			cfg.routes.get("/docs", DocsRoutes.scalarHtml());
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

			OptionsHandler optionsHandler = new OptionsHandler(config, queue, gameClient);
			cfg.routes.get("/v1/options", optionsHandler, McdtRole.READ);
			cfg.routes.patch("/v1/options", optionsHandler, McdtRole.ACT);

			cfg.routes.get("/v1/screenshot", new ScreenshotHandler(config, queue, gameClient), McdtRole.READ);

			ConfirmManager confirmManager = new ConfirmManager();
			cfg.routes.post("/v1/input/keys", new InputKeysHandler(config, queue, gameClient), McdtRole.ACT);
			cfg.routes.post("/v1/input/mouse", new InputMouseHandler(config, queue, gameClient), McdtRole.ACT);

			ChatHandler chatHandler = new ChatHandler(config, queue, gameClient);
			cfg.routes.post("/v1/chat", chatHandler, McdtRole.ACT);
			cfg.routes.get("/v1/chat", chatHandler, McdtRole.READ);

			cfg.routes.post("/v1/player/look", new PlayerLookHandler(config, queue, gameClient), McdtRole.ACT);
			cfg.routes.post("/v1/player/move", new PlayerMoveHandler(config, queue, gameClient), McdtRole.ACT);
			cfg.routes.post("/v1/player/actions", new PlayerActionsHandler(config, queue, gameClient), McdtRole.ACT);

			cfg.routes.post("/v1/screen/actions", new ScreenActionsHandler(config, queue, gameClient), McdtRole.ACT);
			cfg.routes.post("/v1/screen/slots", new ScreenSlotsHandler(config, queue, gameClient), McdtRole.ACT);

			cfg.routes.post("/v1/session/quit", new QuitHandler(config, confirmManager), McdtRole.ACT);
			cfg.routes.post("/v1/session/quit/confirm", new QuitConfirmHandler(config, confirmManager, gameClient, () -> {
				dev.advik.mcdt.events.EventHub.getInstance().closeAll(1001, "Server shutting down (quit)");
				onQuitCallback.run();
			}), McdtRole.ACT);

			// --- Milestone M3 Reflection Routes ---
			HandleTable handleTable = HandleTable.getInstance();
			NameService nameService = new NameService();
			nameService.initialize(env.configDir(), env.gameVersion());
			ReflectionCache reflectionCache = new ReflectionCache(nameService);
			PathEvaluator pathEvaluator = new PathEvaluator(reflectionCache);
			ReflectionSerializer reflectionSerializer = new ReflectionSerializer(handleTable, nameService, reflectionCache);

			cfg.routes.get("/v1/objects/roots", new ObjectsRootsHandler(config, queue, gameClient, nameService), McdtRole.READ);
			cfg.routes.get("/v1/objects/{handle}", new ObjectInspectHandler(config, queue, gameClient, handleTable, pathEvaluator, reflectionSerializer), McdtRole.READ);
			cfg.routes.get("/v1/objects/{handle}/fields/{name}", new ObjectFieldHandler(config, queue, gameClient, handleTable, reflectionCache, reflectionSerializer), McdtRole.READ);
			cfg.routes.put("/v1/objects/{handle}/fields/{name}", new ObjectFieldSetHandler(config, queue, gameClient, handleTable, reflectionCache, reflectionSerializer), McdtRole.REFLECT_WRITE);
			cfg.routes.post("/v1/objects/{handle}/invoke", new ObjectInvokeHandler(config, queue, gameClient, handleTable, reflectionCache, reflectionSerializer), McdtRole.REFLECT_WRITE);
			cfg.routes.get("/v1/classes/{name}", new ClassInspectHandler(config, queue, nameService, reflectionSerializer), McdtRole.READ);
			cfg.routes.delete("/v1/objects/{handle}", new ObjectDeleteHandler(handleTable), McdtRole.READ);

			// --- Milestone M5 Raw Packet Send ---
			cfg.routes.post("/v1/packets", new PacketsHandler(config, queue, gameClient, handleTable), McdtRole.REFLECT_WRITE);

			// --- Milestone M5 Baritone Routes ---
			cfg.routes.get("/v1/baritone", new BaritoneHandlers.StatusHandler(config, queue, baritoneService), McdtRole.READ);
			cfg.routes.get("/v1/baritone/status", new BaritoneHandlers.StatusHandler(config, queue, baritoneService), McdtRole.READ);
			cfg.routes.get("/v1/baritone/path", new BaritoneHandlers.PathHandler(config, queue, baritoneService), McdtRole.READ);
			cfg.routes.post("/v1/baritone/goto", new BaritoneHandlers.GotoHandler(config, queue, baritoneService), McdtRole.ACT);
			cfg.routes.post("/v1/baritone/mine", new BaritoneHandlers.MineHandler(config, queue, baritoneService), McdtRole.ACT);
			cfg.routes.post("/v1/baritone/follow", new BaritoneHandlers.FollowHandler(config, queue, baritoneService), McdtRole.ACT);
			cfg.routes.post("/v1/baritone/explore", new BaritoneHandlers.ExploreHandler(config, queue, baritoneService), McdtRole.ACT);
			cfg.routes.post("/v1/baritone/farm", new BaritoneHandlers.FarmHandler(config, queue, baritoneService), McdtRole.ACT);
			cfg.routes.post("/v1/baritone/build", new BaritoneHandlers.BuildHandler(config, queue, baritoneService), McdtRole.ACT);
			cfg.routes.post("/v1/baritone/cancel", new BaritoneHandlers.CancelHandler(config, queue, baritoneService), McdtRole.ACT);
			cfg.routes.get("/v1/baritone/jobs/{id}", new BaritoneHandlers.JobHandler(baritoneService), McdtRole.READ);
			cfg.routes.get("/v1/baritone/settings", new BaritoneHandlers.SettingsGetHandler(baritoneService), McdtRole.READ);
			cfg.routes.patch("/v1/baritone/settings", new BaritoneHandlers.SettingsPatchHandler(baritoneService), McdtRole.ACT);
			cfg.routes.post("/v1/baritone/command", new BaritoneHandlers.CommandHandler(config, queue, baritoneService), McdtRole.ACT);

			// --- Milestone M4 WebSocket Routes ---
			dev.advik.mcdt.events.EventHub eventHub = dev.advik.mcdt.events.EventHub.getInstance();
			cfg.routes.ws("/v1/events", new EventsWsHandler(config, token, boundPort::get, eventHub));
			cfg.routes.ws("/v1/input/keys", new InputKeysWsHandler(config, token, boundPort::get, queue, gameClient));
			cfg.routes.ws("/v1/input/mouse", new InputMouseWsHandler(config, token, boundPort::get, queue, gameClient));
			cfg.routes.ws("/v1/player/inventory", new InventoryWsHandler(config, token, boundPort::get, queue, gameClient));
			cfg.routes.ws("/v1/player/status", new PlayerStatusWsHandler(config, token, boundPort::get, queue, gameClient));
			cfg.routes.ws("/v1/world/stream", new WorldStreamWsHandler(config, token, boundPort::get, queue, gameClient));

			// --- Milestone M6 MCP Routes ---
			dev.advik.mcdt.mcp.McpToolLog toolLog = new dev.advik.mcdt.mcp.McpToolLog(100);
			dev.advik.mcdt.mcp.McpToolRegistry toolRegistry = new dev.advik.mcdt.mcp.McpToolRegistry(
					config,
					token,
					boundPort::get,
					queue,
					gameClient,
					baritoneService,
					handleTable,
					nameService,
					pathEvaluator,
					reflectionSerializer
			);
			dev.advik.mcdt.mcp.McpResourceRegistry resourceRegistry = new dev.advik.mcdt.mcp.McpResourceRegistry(gameClient);
			dev.advik.mcdt.mcp.McpHandler mcpHandler = new dev.advik.mcdt.mcp.McpHandler(
					env,
					config,
					queue,
					mcpSessionManager,
					toolRegistry,
					resourceRegistry,
					toolLog
			);

			cfg.routes.post("/mcp", mcpHandler::handlePost, McdtRole.READ);
			cfg.routes.sse("/mcp", mcpHandler::handleSse, McdtRole.READ);
			cfg.routes.get("/v1/mcp/status", mcpHandler::handleStatus, McdtRole.READ);
			cfg.routes.get("/v1/mcp/log", mcpHandler::handleLog, McdtRole.READ);

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
			if (targetPort != 0 && isBindException(e)) {
				Mcdt.LOGGER.warn("Port {} is in use; trying a random free port...", targetPort);
				app.start(host, 0);
				boundPort.set(app.port());
				Mcdt.LOGGER.info("MCDT HTTP server started at http://{}:{}/", host, app.port());
			} else {
				throw new IllegalStateException("Failed to start MCDT HTTP server on " + host + ":" + targetPort, e);
			}
		}
	}

	private static boolean isBindException(Throwable t) {
		while (t != null) {
			if (t instanceof BindException || (t.getMessage() != null && t.getMessage().toLowerCase().contains("failed to bind"))) {
				return true;
			}
			t = t.getCause();
		}
		return false;
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

	public void setOnQuitCallback(Runnable callback) {
		this.onQuitCallback = callback != null ? callback : () -> {};
	}

	@Override
	public void close() {
		try {
			if (mcpSessionManager != null) {
				mcpSessionManager.close();
			}
			dev.advik.mcdt.events.EventHub.getInstance().closeAll(1000, "Server stopping");
			app.stop();
		} catch (Exception e) {
			Mcdt.LOGGER.warn("Error stopping Javalin server: {}", e.getMessage());
		}
	}
}
