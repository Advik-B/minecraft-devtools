package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.BatchDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.ProblemException;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for POST /v1/batch.
 * Executes up to 32 read requests atomically in a single client task on the exact same game tick.
 */
public final class BatchHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public BatchHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/batch",
			methods = {HttpMethod.POST},
			summary = "Batch multiple read queries in a single tick",
			description = "Executes up to 32 GET operations atomically within a single client tick. Results are indexed by request id.",
			tags = {"Batch"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = BatchDto.Request.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Batched results indexed by id",
							content = {@OpenApiContent(from = BatchDto.Response.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Bad request - invalid payload, excessive batch size, or write operations included",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "503",
							description = "Task queue full",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "504",
							description = "Gateway timeout",
							content = {@OpenApiContent(from = Problem.class)}
					)
			}
	)
	@Override
	public void handle(Context ctx) {
		BatchDto.Request request;
		try {
			request = ctx.bodyAsClass(BatchDto.Request.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for batch request");
		}

		if (request == null || request.requests() == null || request.requests().isEmpty()) {
			throw Problems.invalidParameter("requests", "Batch request list cannot be empty");
		}

		if (request.requests().size() > 32) {
			throw Problems.batchLimitExceeded(request.requests().size(), 32);
		}

		// Validate each request
		Set<String> writePrefixes = Set.of(
				"/v1/input",
				"/v1/player/look",
				"/v1/player/move",
				"/v1/player/actions",
				"/v1/screen/actions",
				"/v1/screen/slots",
				"/v1/recipes/place",
				"/v1/session/pause",
				"/v1/session/disconnect",
				"/v1/session/lan",
				"/v1/worlds/",
				"/v1/servers/connect",
				"/v1/session/quit",
				"/v1/packets",
				"/v1/baritone/goto",
				"/v1/baritone/mine",
				"/v1/baritone/follow",
				"/v1/baritone/explore",
				"/v1/baritone/farm",
				"/v1/baritone/build",
				"/v1/baritone/cancel",
				"/v1/baritone/command"
		);

		for (BatchDto.Request.SubRequest sub : request.requests()) {
			if (sub.id() == null || sub.id().isBlank()) {
				throw Problems.invalidParameter("id", "Sub-request id must not be empty");
			}
			if (sub.path() == null || !sub.path().startsWith("/v1/")) {
				throw Problems.invalidParameter("path", "Sub-request path must begin with /v1/");
			}
			String rawPath = sub.path().contains("?") ? sub.path().substring(0, sub.path().indexOf('?')) : sub.path();
			for (String writePref : writePrefixes) {
				if (rawPath.startsWith(writePref)) {
					throw Problems.badRequest("Batch endpoint only permits read operations. Write route not allowed: " + rawPath);
				}
			}
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			Map<String, BatchDto.Response.SubResponse> results = new LinkedHashMap<>();

			for (BatchDto.Request.SubRequest sub : request.requests()) {
				try {
					Object data = executeSubRequest(sub.path());
					results.put(sub.id(), new BatchDto.Response.SubResponse(200, data, null));
				} catch (ProblemException pe) {
					results.put(sub.id(), new BatchDto.Response.SubResponse(pe.problem().status(), null, pe.problem()));
				} catch (Exception e) {
					results.put(sub.id(), new BatchDto.Response.SubResponse(500, null, Problems.internal(e.getMessage()).problem()));
				}
			}

			BatchDto.Response response = new BatchDto.Response(0L, results);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");

			// Inject actual tick into top-level response
			var root = (com.fasterxml.jackson.databind.node.ObjectNode) result.body();
			root.put("tick", result.tick());

			ctx.result(root.toString());
		});

		ctx.future(() -> future);
	}

	private Object executeSubRequest(String rawUrl) {
		URI uri = URI.create(rawUrl);
		String path = uri.getPath();
		Map<String, String> query = parseQueryParams(uri.getRawQuery());

		return switch (path) {
			case "/v1/client" -> gameClient.getClient();
			case "/v1/player" -> gameClient.getPlayer();
			case "/v1/player/inventory" -> gameClient.getInventory();
			case "/v1/player/skin/info" -> gameClient.getPlayerSkinInfo(query.get("player"), false);
			case "/v1/world" -> gameClient.getWorld();
			case "/v1/world/blocks" -> {
				int[] from = parseCoords(query.get("from"), "from");
				int[] to = parseCoords(query.get("to"), "to");
				yield gameClient.getBlocks(from[0], from[1], from[2], to[0], to[1], to[2]);
			}
			case "/v1/world/entities" -> {
				Double radius = query.containsKey("radius") ? Double.parseDouble(query.get("radius")) : null;
				String type = query.get("type");
				Integer limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : null;
				yield gameClient.getEntities(radius, type, null, limit);
			}
			case "/v1/world/entities/categories" -> gameClient.getEntityCategories();
			case "/v1/world/raycast" -> {
				Double maxDist = query.containsKey("maxDistance") ? Double.parseDouble(query.get("maxDistance")) : null;
				Boolean fluids = query.containsKey("fluids") ? Boolean.parseBoolean(query.get("fluids")) : true;
				Boolean entities = query.containsKey("entities") ? Boolean.parseBoolean(query.get("entities")) : false;
				yield gameClient.raycast(null, null, maxDist, fluids, entities);
			}
			case "/v1/screen" -> gameClient.getScreen();
			case "/v1/registries" -> gameClient.getRegistries();
			case "/v1/options" -> gameClient.getOptions();
			case "/v1/camera" -> gameClient.getCamera();
			case "/v1/window" -> gameClient.getWindow();
			case "/v1/debug" -> gameClient.getDebug();
			case "/v1/keybinds" -> gameClient.getKeybinds();
			case "/v1/resourcepacks" -> gameClient.getResourcePacks();
			case "/v1/textures/atlases" -> gameClient.getLoadedAtlases();
			case "/v1/textures/atlas" -> gameClient.getAtlasDetails(query.get("id"), false);
			case "/v1/chat" -> {
				int offset = query.containsKey("offset") ? Integer.parseInt(query.get("offset")) : 0;
				int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 50;
				yield gameClient.getChatHistory(offset, limit);
			}
			case "/v1/recipes" -> {
				Boolean craftable = query.containsKey("craftable") ? Boolean.parseBoolean(query.get("craftable")) : null;
				yield gameClient.getRecipes(craftable, query.get("category"), query.get("search"));
			}
			case "/v1/session" -> gameClient.getSession();
			case "/v1/worlds" -> gameClient.getWorlds();
			case "/v1/servers" -> gameClient.getServers();
			case "/v1/stats" -> {
				yield gameClient.getStats(query.get("category"));
			}
			case "/v1/advancements" -> {
				Boolean done = query.containsKey("done") ? Boolean.parseBoolean(query.get("done")) : null;
				yield gameClient.getAdvancements(done, query.get("search"));
			}
			case "/v1/hud" -> {
				Set<String> inc = query.containsKey("include") ? new HashSet<>(Arrays.asList(query.get("include").toLowerCase().split(","))) : null;
				yield gameClient.getHud(inc);
			}
			case "/v1/view/blocks" -> {
				Double maxDist = query.containsKey("maxDistance") ? Double.parseDouble(query.get("maxDistance")) : null;
				Boolean fluids = query.containsKey("fluids") ? Boolean.parseBoolean(query.get("fluids")) : true;
				Boolean pass = query.containsKey("passThrough") ? Boolean.parseBoolean(query.get("passThrough")) : true;
				Boolean entities = query.containsKey("entities") ? Boolean.parseBoolean(query.get("entities")) : false;
				Boolean grid = query.containsKey("grid") ? Boolean.parseBoolean(query.get("grid")) : false;
				Double minCov = query.containsKey("minCoverage") ? Double.parseDouble(query.get("minCoverage")) : 0.0;
				yield gameClient.getViewBlocks(query.get("res"), maxDist, fluids, pass, entities, grid, minCov);
			}
			case "/v1/view/blocks/summary" -> {
				Double maxDist = query.containsKey("maxDistance") ? Double.parseDouble(query.get("maxDistance")) : null;
				yield gameClient.getViewBlocksSummary(maxDist);
			}
			default -> {
				if (path.startsWith("/v1/registries/")) {
					String registryId = path.substring("/v1/registries/".length());
					String q = query.get("query");
					int offset = query.containsKey("offset") ? Integer.parseInt(query.get("offset")) : 0;
					int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 50;
					yield gameClient.getRegistryEntries(registryId, q, offset, limit);
				}
				throw Problems.notFound("Endpoint not found: " + path);
			}
		};
	}

	private static Map<String, String> parseQueryParams(String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) return Collections.emptyMap();
		Map<String, String> map = new HashMap<>();
		for (String param : rawQuery.split("&")) {
			String[] pair = param.split("=", 2);
			if (pair.length == 2) {
				map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
			} else if (pair.length == 1) {
				map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
			}
		}
		return map;
	}

	private static int[] parseCoords(String str, String paramName) {
		if (str == null || str.isBlank()) {
			throw Problems.invalidCoordinates("Missing required coordinate parameter: " + paramName);
		}
		String[] parts = str.split(",");
		if (parts.length != 3) {
			throw Problems.invalidCoordinates("Parameter '" + paramName + "' must have 3 comma-separated integers (x,y,z). Got: " + str);
		}
		try {
			return new int[]{
					Integer.parseInt(parts[0].trim()),
					Integer.parseInt(parts[1].trim()),
					Integer.parseInt(parts[2].trim())
			};
		} catch (NumberFormatException e) {
			throw Problems.invalidCoordinates("Parameter '" + paramName + "' contains invalid integer coordinate in: " + str);
		}
	}
}
