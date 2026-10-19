package dev.advik.mcdt.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.advik.mcdt.api.v1.dto.*;
import dev.advik.mcdt.baritone.BaritoneService;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.mcp.dto.McpProtocolDto.*;
import dev.advik.mcdt.reflection.*;
import dev.advik.mcdt.security.BearerToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/**
 * Registry of curated MCP tools, schemas, and execution dispatcher.
 */
public final class McpToolRegistry {
	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final BaritoneService baritoneService;
	private final HandleTable handleTable;
	private final NameService nameService;
	private final PathEvaluator pathEvaluator;
	private final ReflectionSerializer reflectionSerializer;
	private final HttpClient internalHttpClient;

	public McpToolRegistry(
			McdtConfig config,
			BearerToken token,
			IntSupplier portSupplier,
			TaskQueue queue,
			GameClient gameClient,
			BaritoneService baritoneService,
			HandleTable handleTable,
			NameService nameService,
			PathEvaluator pathEvaluator,
			ReflectionSerializer reflectionSerializer
	) {
		this.config = config;
		this.token = token;
		this.portSupplier = portSupplier;
		this.queue = queue;
		this.gameClient = gameClient;
		this.baritoneService = baritoneService;
		this.handleTable = handleTable;
		this.nameService = nameService;
		this.pathEvaluator = pathEvaluator;
		this.reflectionSerializer = reflectionSerializer;
		this.internalHttpClient = HttpClient.newHttpClient();
	}

	public List<ToolDefinition> listTools() {
		List<ToolDefinition> list = new ArrayList<>();
		Permission perm = config.permission;

		// 1. READ Tools
		if (perm.grants(Permission.READ)) {
			list.add(new ToolDefinition(
					"get_state",
					"Retrieve the consolidated state of the Minecraft client in a single call. Returns player position, rotation, health, hunger, effects, gamemode, dimension, loaded chunks, weather, time, held items, open screen, and Baritone status. Use this to orient the agent at the start or after actions.",
					emptySchema()
			));

			list.add(new ToolDefinition(
					"screenshot",
					"Capture a screenshot of the current Minecraft client viewport and return it as an image content block. Vision-capable models can inspect the game screen directly. Optional parameter 'scale' (between 0.1 and 1.0, default 0.5) controls downsampling.",
					screenshotSchema()
			));

			list.add(new ToolDefinition(
					"raycast",
					"Perform a line-of-sight raycast from the player crosshair or custom coordinates to inspect what block or entity is targeted. Returns hit coordinates, normal face, block state, or entity info. Max distance up to 128 blocks.",
					raycastSchema()
			));

			list.add(new ToolDefinition(
					"get_player_skin",
					"Retrieve player skin image and metadata. Returns model type (wide or slim), skin texture metadata, and the skin image content block. Optional parameter 'player' specifies a target player name or UUID (defaults to local player).",
					playerSkinSchema()
			));

			list.add(new ToolDefinition(
					"get_game_textures",
					"Retrieve loaded game texture atlas metadata and sprite UV coordinates (e.g. for blocks, particles, gui). Default atlas is 'blocks'. Optional parameter 'id' selects atlas name/alias, and 'embed_image' (boolean) embeds the stitched texture image.",
					gameTexturesSchema()
			));

			list.add(new ToolDefinition(
					"job_status",
					"Query the status and execution state of a background job (such as a Baritone task or timed movement) by job_id.",
					jobStatusSchema()
			));

			list.add(new ToolDefinition(
					"inspect",
					"Escape hatch to walk Minecraft's live reflection object graph starting from root handles ('minecraft', 'player', 'level', 'screen', 'options', 'gameRenderer') with an optional getter/field path query (e.g. 'player.getInventory().items[0]').",
					inspectSchema()
			));

			list.add(new ToolDefinition(
					"rest_call",
					"Escape hatch to call any REST endpoint on the MCDT server (e.g. GET /v1/options, POST /v1/chat, GET /v1/registries). Automatically enforces current permissions on the target method/route.",
					restCallSchema()
			));
		}

		// 2. ACT Tools
		if (perm.grants(Permission.ACT)) {
			list.add(new ToolDefinition(
					"look",
					"Set player rotation (yaw and pitch in degrees) or look at target coordinates (x, y, z). Yaw ranges from -180 to 180 (0 is South, 90 is West, 180/-180 is North, -90 is East). Pitch ranges from -90 (straight up) to 90 (straight down). Call raycast or get_state next to verify view.",
					lookSchema()
			));

			list.add(new ToolDefinition(
					"move",
					"Drive player movement inputs (forward, back, strafeLeft, strafeRight, jump, sneak, sprint) for a specified number of game ticks (20 ticks = 1 second) or continuously until stopped. If ticks > 0, executes timed movement.",
					moveSchema()
			));

			list.add(new ToolDefinition(
					"interact",
					"Perform a player interaction in the game world or with a held item. Actions: 'attack' (left click / attack entity or break block), 'use' (right click / use item or block), 'swing' (hand swing), 'pick_block' (middle click), 'drop' (drop held item), 'swap_hands' (swap offhand). Optional target: block pos [x, y, z], face, or entityId.",
					interactSchema()
			));

			list.add(new ToolDefinition(
					"inventory_click",
					"Click or manipulate a slot in the player inventory or currently open container screen. Slots 0-8 are hotbar, 9-35 main inventory, 36-39 armor, 40 offhand. In container screens, container slots start at 0. Mode can be 'pickup' (left/right click), 'quick_move' (shift click), 'swap' (swap with hotbar 0-8), 'throw' (drop item), 'clone' (creative middle click).",
					inventoryClickSchema()
			));

			list.add(new ToolDefinition(
					"screen_action",
					"Interact with the currently open GUI screen or close it. Actions: 'click' (click widget by id or label), 'close' (close the open screen), 'type' (type text into an active text field or widget).",
					screenActionSchema()
			));

			list.add(new ToolDefinition(
					"chat",
					"Send a chat message or slash command (e.g. '/time set day' or 'Hello'), or read recent chat history. Provide 'message' to send, or specify 'read': true to get recent history.",
					chatSchema()
			));

			// Baritone Tools (if installed and enabled)
			if (baritoneService.isInstalled() && config.enableBaritone) {
				list.add(new ToolDefinition(
						"baritone_goto",
						"Order Baritone to pathfind and travel to target coordinates (x, y, z) or a block type. Returns a job_id immediately without blocking. Call job_status with the job_id to monitor progress, or baritone_cancel to stop.",
						baritoneGotoSchema()
				));

				list.add(new ToolDefinition(
						"baritone_mine",
						"Order Baritone to search for and mine a specified block type (e.g. 'minecraft:diamond_ore') or mine at coordinates. Returns a job_id immediately. Call job_status to monitor progress.",
						baritoneMineSchema()
				));

				list.add(new ToolDefinition(
						"baritone_follow",
						"Order Baritone to follow an entity by name, UUID, or type (e.g. 'Player', 'cow', 'zombie'). Returns a job_id immediately. Call baritone_cancel to stop following.",
						baritoneFollowSchema()
				));

				list.add(new ToolDefinition(
						"baritone_cancel",
						"Cancel the currently active Baritone pathfinding, mining, following, or building process.",
						emptySchema()
				));
			}
		}

		return list;
	}

	public CompletableFuture<ToolCallResult> callTool(String name, JsonNode arguments) {
		JsonNode args = arguments != null && !arguments.isNull() ? arguments : Json.MAPPER.createObjectNode();

		// Permission and multiplayer verification
		Permission required = requiredPermissionForTool(name);
		if (!config.permission.grants(required)) {
			return CompletableFuture.completedFuture(ToolCallResult.error(
					"Permission denied: Tool '" + name + "' requires permission '" + required.id()
							+ "', but server permission is '" + config.permission.id() + "'."
			));
		}

		if (gameClient.isMultiplayer() && (required == Permission.ACT || required == Permission.REFLECT_WRITE)) {
			String serverAddress = gameClient.getCurrentServerAddress();
			boolean allowed = serverAddress != null && config.allowedMultiplayerServers.stream()
					.anyMatch(serverAddress::equalsIgnoreCase);
			if (!allowed) {
				return CompletableFuture.completedFuture(ToolCallResult.error(
						"Multiplayer security denied write action: Connected server '"
								+ (serverAddress != null ? serverAddress : "unknown")
								+ "' is not in allowedMultiplayerServers list."
				));
			}
		}

		long timeoutMs = config.requestTimeoutMs;

		try {
			return switch (name) {
				case "get_state" -> executeGetState(timeoutMs);
				case "screenshot" -> executeScreenshot(args, timeoutMs);
				case "raycast" -> executeRaycast(args, timeoutMs);
				case "get_player_skin" -> executeGetPlayerSkin(args, timeoutMs);
				case "get_game_textures" -> executeGetGameTextures(args, timeoutMs);
				case "job_status" -> executeJobStatus(args);
				case "inspect" -> executeInspect(args, timeoutMs);
				case "rest_call" -> executeRestCall(args);
				case "look" -> executeLook(args, timeoutMs);
				case "move" -> executeMove(args, timeoutMs);
				case "interact" -> executeInteract(args, timeoutMs);
				case "inventory_click" -> executeInventoryClick(args, timeoutMs);
				case "screen_action" -> executeScreenAction(args, timeoutMs);
				case "chat" -> executeChat(args, timeoutMs);
				case "baritone_goto" -> executeBaritoneGoto(args);
				case "baritone_mine" -> executeBaritoneMine(args);
				case "baritone_follow" -> executeBaritoneFollow(args);
				case "baritone_cancel" -> executeBaritoneCancel();
				default -> CompletableFuture.completedFuture(ToolCallResult.error("Unknown tool: " + name));
			};
		} catch (Exception e) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Error executing tool " + name + ": " + e.getMessage()));
		}
	}

	private Permission requiredPermissionForTool(String name) {
		return switch (name) {
			case "get_state", "screenshot", "raycast", "job_status", "inspect", "get_player_skin", "get_game_textures" -> Permission.READ;
			case "rest_call" -> Permission.READ; // Target method checked in handler
			case "look", "move", "interact", "inventory_click", "screen_action", "chat",
					"baritone_goto", "baritone_mine", "baritone_follow", "baritone_cancel" -> Permission.ACT;
			default -> Permission.REFLECT_WRITE;
		};
	}

	// Tool Executions

	private CompletableFuture<ToolCallResult> executeGetState(long timeoutMs) {
		return queue.submit(() -> {
			Map<String, Object> state = new LinkedHashMap<>();
			state.put("client", gameClient.getClient());

			try {
				state.put("world", gameClient.getWorld());
			} catch (Exception ignored) {}

			try {
				state.put("player", gameClient.getPlayer());
				state.put("inventory", gameClient.getInventory());
			} catch (Exception ignored) {}

			if (baritoneService.isInstalled()) {
				try {
					state.put("baritone", baritoneService.getStatus());
				} catch (Exception ignored) {}
			}

			return Json.MAPPER.valueToTree(state);
		}, timeoutMs).thenApply(taskResult -> ToolCallResult.text(taskResult.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Failed to retrieve state: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeScreenshot(JsonNode args, long timeoutMs) {
		float scale = 0.5f;
		if (args.has("scale") && args.get("scale").isNumber()) {
			scale = (float) args.get("scale").asDouble();
			scale = Math.max(0.1f, Math.min(1.0f, scale));
		}
		float finalScale = scale;

		CompletableFuture<byte[]> future = new CompletableFuture<>();
		try {
			queue.submit(() -> {
				try {
					byte[] bytes = gameClient.captureScreenshot(finalScale);
					future.complete(bytes);
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
				return Json.MAPPER.nullNode();
			}, timeoutMs).exceptionally(ex -> {
				future.completeExceptionally(ex);
				return null;
			});
		} catch (Exception e) {
			future.completeExceptionally(e);
		}

		return future.thenApply(bytes -> {
			String base64 = Base64.getEncoder().encodeToString(bytes);
			return ToolCallResult.image(base64, "image/png");
		}).exceptionally(ex -> ToolCallResult.error("Failed to capture screenshot: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeRaycast(JsonNode args, long timeoutMs) {
		Double maxDist = args.has("maxDistance") ? args.get("maxDistance").asDouble(5.0) : 5.0;
		Boolean fluids = args.has("includeFluids") ? args.get("includeFluids").asBoolean(false) : false;
		Boolean entities = !args.has("entities") || args.get("entities").asBoolean(true);

		return queue.submit(() -> {
			RaycastDto response = gameClient.raycast(null, null, maxDist, fluids, entities);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Raycast failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeGetPlayerSkin(JsonNode args, long timeoutMs) {
		String targetPlayer = args != null && args.has("player") && !args.get("player").isNull()
				? args.get("player").asText()
				: null;

		CompletableFuture<ToolCallResult> future = new CompletableFuture<>();
		try {
			queue.submit(() -> {
				try {
					PlayerSkinDto skinInfo = gameClient.getPlayerSkinInfo(targetPlayer, false);
					byte[] skinBytes = gameClient.getPlayerSkinImage(targetPlayer);
					String base64 = Base64.getEncoder().encodeToString(skinBytes);
					String jsonText = Json.MAPPER.valueToTree(skinInfo).toPrettyString();

					List<ContentBlock> blocks = List.of(
							ContentBlock.text(jsonText),
							ContentBlock.image(base64, "image/png")
					);
					future.complete(new ToolCallResult(blocks, false));
				} catch (Throwable t) {
					future.complete(ToolCallResult.error("Failed to get player skin: " + t.getMessage()));
				}
				return Json.MAPPER.nullNode();
			}, timeoutMs).exceptionally(ex -> {
				future.complete(ToolCallResult.error("Failed to get player skin: " + ex.getMessage()));
				return null;
			});
		} catch (Exception e) {
			future.complete(ToolCallResult.error("Failed to submit skin task: " + e.getMessage()));
		}
		return future;
	}

	private CompletableFuture<ToolCallResult> executeGetGameTextures(JsonNode args, long timeoutMs) {
		String atlasId = args != null && args.has("id") && !args.get("id").isNull()
				? args.get("id").asText()
				: "blocks";
		boolean embedImage = args != null && args.has("embed_image") && args.get("embed_image").asBoolean();

		CompletableFuture<ToolCallResult> future = new CompletableFuture<>();
		try {
			queue.submit(() -> {
				try {
					TextureAtlasDto atlasInfo = gameClient.getAtlasDetails(atlasId, embedImage);
					String jsonText = Json.MAPPER.valueToTree(atlasInfo).toPrettyString();
					if (embedImage) {
						byte[] imageBytes = gameClient.getAtlasImage(atlasId);
						String base64 = Base64.getEncoder().encodeToString(imageBytes);
						future.complete(new ToolCallResult(List.of(
								ContentBlock.text(jsonText),
								ContentBlock.image(base64, "image/png")
						), false));
					} else {
						future.complete(ToolCallResult.text(jsonText));
					}
				} catch (Throwable t) {
					future.complete(ToolCallResult.error("Failed to get game textures: " + t.getMessage()));
				}
				return Json.MAPPER.nullNode();
			}, timeoutMs).exceptionally(ex -> {
				future.complete(ToolCallResult.error("Failed to get game textures: " + ex.getMessage()));
				return null;
			});
		} catch (Exception e) {
			future.complete(ToolCallResult.error("Failed to submit texture task: " + e.getMessage()));
		}
		return future;
	}

	private CompletableFuture<ToolCallResult> executeJobStatus(JsonNode args) {
		String jobId = args.has("job_id") ? args.get("job_id").asText() : null;
		if (jobId == null || jobId.isBlank()) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Missing required parameter: job_id"));
		}

		if (baritoneService.isInstalled()) {
			try {
				var job = baritoneService.getJob(jobId);
				return CompletableFuture.completedFuture(ToolCallResult.text(Json.MAPPER.valueToTree(job).toPrettyString()));
			} catch (Exception ignored) {}
		}

		return CompletableFuture.completedFuture(ToolCallResult.error("Job not found: " + jobId));
	}

	private CompletableFuture<ToolCallResult> executeInspect(JsonNode args, long timeoutMs) {
		String handle = args.has("handle") ? args.get("handle").asText() : null;
		if (handle == null || handle.isBlank()) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Missing required parameter: handle"));
		}
		String path = args.has("path") ? args.get("path").asText() : null;

		return queue.submit(() -> {
			Object target;
			if (HandleTable.isRoot(handle)) {
				target = gameClient.getRootObject(handle);
				if (target == null) {
					if ("player".equals(handle)) {
						throw Problems.noPlayer();
					} else if ("level".equals(handle)) {
						throw Problems.noWorld();
					} else if ("screen".equals(handle)) {
						throw Problems.noScreen();
					} else {
						throw Problems.notFound("Root object '" + handle + "' is currently null");
					}
				}
			} else {
				target = handleTable.get(handle);
			}

			if (path != null && !path.isBlank()) {
				Object evaluated = pathEvaluator.evaluate(target, path);
				if (evaluated == null) {
					return Json.MAPPER.valueToTree(Map.of("handle", handle, "path", path, "value", "null"));
				}
				String subHandle = handleTable.getOrCreateHandle(evaluated);
				Map<String, Object> inspected = reflectionSerializer.inspectObject(evaluated, subHandle, 1, null, null);
				inspected.put("path", path);
				return Json.MAPPER.valueToTree(inspected);
			} else {
				return Json.MAPPER.valueToTree(reflectionSerializer.inspectObject(target, handle, 1, null, null));
			}
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Inspect failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeRestCall(JsonNode args) {
		String method = args.has("method") ? args.get("method").asText().toUpperCase() : "GET";
		String path = args.has("path") ? args.get("path").asText() : null;
		if (path == null || path.isBlank()) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Missing required parameter: path"));
		}

		if (!path.startsWith("/")) {
			path = "/" + path;
		}

		// Verify permission for HTTP method
		if (!"GET".equals(method) && !config.permission.grants(Permission.ACT)) {
			return CompletableFuture.completedFuture(ToolCallResult.error(
					"Permission denied: " + method + " requests require 'act' permission."
			));
		}

		int port = portSupplier.getAsInt();
		String url = "http://127.0.0.1:" + port + path;

		try {
			HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.header("Authorization", "Bearer " + token.value())
					.header("Host", "127.0.0.1:" + port);

			JsonNode bodyNode = args.get("body");
			String bodyStr = bodyNode != null && !bodyNode.isNull() ? bodyNode.toString() : "";

			switch (method) {
				case "GET" -> reqBuilder.GET();
				case "POST" -> reqBuilder.POST(HttpRequest.BodyPublishers.ofString(bodyStr)).header("Content-Type", "application/json");
				case "PUT" -> reqBuilder.PUT(HttpRequest.BodyPublishers.ofString(bodyStr)).header("Content-Type", "application/json");
				case "PATCH" -> reqBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(bodyStr)).header("Content-Type", "application/json");
				case "DELETE" -> reqBuilder.DELETE();
				default -> {
					return CompletableFuture.completedFuture(ToolCallResult.error("Unsupported HTTP method: " + method));
				}
			}

			return internalHttpClient.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
					.thenApply(resp -> {
						if (resp.statusCode() >= 400) {
							return ToolCallResult.error("HTTP " + resp.statusCode() + ": " + resp.body());
						}
						return ToolCallResult.text(resp.body());
					}).exceptionally(ex -> ToolCallResult.error("REST call failed: " + ex.getMessage()));
		} catch (Exception e) {
			return CompletableFuture.completedFuture(ToolCallResult.error("REST request error: " + e.getMessage()));
		}
	}

	private CompletableFuture<ToolCallResult> executeLook(JsonNode args, long timeoutMs) {
		PlayerLookDto.Request req = Json.MAPPER.convertValue(args, PlayerLookDto.Request.class);
		return queue.submit(() -> {
			PlayerLookDto.Response resp = gameClient.setPlayerLook(req);
			return Json.MAPPER.valueToTree(resp);
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Look action failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeMove(JsonNode args, long timeoutMs) {
		PlayerMoveDto.Request req = Json.MAPPER.convertValue(args, PlayerMoveDto.Request.class);
		return queue.submit(() -> {
			PlayerMoveDto.Response resp = gameClient.setPlayerMove(req);
			return Json.MAPPER.valueToTree(resp);
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Move action failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeInteract(JsonNode args, long timeoutMs) {
		PlayerActionDto.Request req = Json.MAPPER.convertValue(args, PlayerActionDto.Request.class);
		return queue.submit(() -> {
			PlayerActionDto.Response resp = gameClient.performPlayerAction(req);
			return Json.MAPPER.valueToTree(resp);
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Interact action failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeInventoryClick(JsonNode args, long timeoutMs) {
		ScreenSlotDto.Request req = Json.MAPPER.convertValue(args, ScreenSlotDto.Request.class);
		return queue.submit(() -> {
			ScreenSlotDto.Response resp = gameClient.clickScreenSlot(req);
			return Json.MAPPER.valueToTree(resp);
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Inventory click failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeScreenAction(JsonNode args, long timeoutMs) {
		ScreenActionDto.Request req = Json.MAPPER.convertValue(args, ScreenActionDto.Request.class);
		return queue.submit(() -> {
			ScreenActionDto.Response resp = gameClient.performScreenAction(req);
			return Json.MAPPER.valueToTree(resp);
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Screen action failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeChat(JsonNode args, long timeoutMs) {
		if (args.has("read") && args.get("read").asBoolean()) {
			int limit = args.has("limit") ? args.get("limit").asInt(10) : 10;
			return queue.submit(() -> {
				ChatDto.HistoryResponse resp = gameClient.getChatHistory(0, limit);
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
					.exceptionally(ex -> ToolCallResult.error("Chat read failed: " + ex.getMessage()));
		}

		String msg = args.has("message") ? args.get("message").asText() : "";
		ChatDto.SendRequest req = new ChatDto.SendRequest(msg, null);
		return queue.submit(() -> {
			ChatDto.SendResponse resp = gameClient.sendChat(req);
			return Json.MAPPER.valueToTree(resp);
		}, timeoutMs).thenApply(r -> ToolCallResult.text(r.body().toPrettyString()))
				.exceptionally(ex -> ToolCallResult.error("Chat send failed: " + ex.getMessage()));
	}

	private CompletableFuture<ToolCallResult> executeBaritoneGoto(JsonNode args) {
		if (!baritoneService.isInstalled()) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Baritone is not installed."));
		}
		var req = Json.MAPPER.convertValue(args, BaritoneDto.GotoRequest.class);
		var job = baritoneService.startGoto(req);
		return CompletableFuture.completedFuture(ToolCallResult.text(Json.MAPPER.valueToTree(job).toPrettyString()));
	}

	private CompletableFuture<ToolCallResult> executeBaritoneMine(JsonNode args) {
		if (!baritoneService.isInstalled()) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Baritone is not installed."));
		}
		var req = Json.MAPPER.convertValue(args, BaritoneDto.MineRequest.class);
		var job = baritoneService.startMine(req);
		return CompletableFuture.completedFuture(ToolCallResult.text(Json.MAPPER.valueToTree(job).toPrettyString()));
	}

	private CompletableFuture<ToolCallResult> executeBaritoneFollow(JsonNode args) {
		if (!baritoneService.isInstalled()) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Baritone is not installed."));
		}
		var req = Json.MAPPER.convertValue(args, BaritoneDto.FollowRequest.class);
		var job = baritoneService.startFollow(req);
		return CompletableFuture.completedFuture(ToolCallResult.text(Json.MAPPER.valueToTree(job).toPrettyString()));
	}

	private CompletableFuture<ToolCallResult> executeBaritoneCancel() {
		if (!baritoneService.isInstalled()) {
			return CompletableFuture.completedFuture(ToolCallResult.error("Baritone is not installed."));
		}
		var job = baritoneService.cancel();
		return CompletableFuture.completedFuture(ToolCallResult.text(Json.MAPPER.valueToTree(job).toPrettyString()));
	}

	// Schema Helpers

	private static JsonNode emptySchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		schema.putObject("properties");
		return schema;
	}

	private static JsonNode screenshotSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("scale")
				.put("type", "number")
				.put("description", "Scaling factor between 0.1 and 1.0 (default 0.5)");
		return schema;
	}

	private static JsonNode raycastSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("maxDistance").put("type", "number").put("description", "Maximum raycast distance up to 128.0 (default 5.0)");
		props.putObject("includeFluids").put("type", "boolean").put("description", "Whether to hit fluid blocks");
		props.putObject("entities").put("type", "boolean").put("description", "Whether to intersect entities");
		return schema;
	}

	private static JsonNode jobStatusSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("job_id").put("type", "string").put("description", "Unique ID of the background job");
		schema.putArray("required").add("job_id");
		return schema;
	}

	private static JsonNode inspectSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("handle").put("type", "string").put("description", "Root handle name ('minecraft', 'player', etc.) or dynamic handle ID");
		props.putObject("path").put("type", "string").put("description", "Optional property/getter path e.g. 'player.getInventory().items[0]'");
		schema.putArray("required").add("handle");
		return schema;
	}

	private static JsonNode restCallSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("method").put("type", "string").put("description", "HTTP method: GET, POST, PUT, PATCH, DELETE");
		props.putObject("path").put("type", "string").put("description", "Relative API path e.g. '/v1/options'");
		props.putObject("body").put("description", "Optional request body as object or string");
		ArrayNode req = schema.putArray("required");
		req.add("method");
		req.add("path");
		return schema;
	}

	private static JsonNode lookSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("yaw").put("type", "number").put("description", "Target yaw angle in degrees (-180 to 180)");
		props.putObject("pitch").put("type", "number").put("description", "Target pitch angle in degrees (-90 up to 90 down)");
		props.putObject("deltaYaw").put("type", "number").put("description", "Relative yaw rotation in degrees");
		props.putObject("deltaPitch").put("type", "number").put("description", "Relative pitch rotation in degrees");
		props.putObject("target").put("description", "Coordinate target [x, y, z] to look at");
		return schema;
	}

	private static JsonNode moveSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("forward").put("type", "boolean");
		props.putObject("back").put("type", "boolean");
		props.putObject("strafeLeft").put("type", "boolean");
		props.putObject("strafeRight").put("type", "boolean");
		props.putObject("jump").put("type", "boolean");
		props.putObject("sneak").put("type", "boolean");
		props.putObject("sprint").put("type", "boolean");
		props.putObject("ticks").put("type", "integer").put("description", "Optional duration in game ticks (20 ticks = 1 second)");
		return schema;
	}

	private static JsonNode interactSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("action").put("type", "string").put("description", "Interaction: attack, use, swing, pick_block, drop, swap_hands");
		props.putObject("pos").put("description", "Target block coordinates [x, y, z]");
		props.putObject("face").put("type", "string").put("description", "Target face: up, down, north, south, east, west");
		props.putObject("entityId").put("type", "integer").put("description", "Target entity ID");
		schema.putArray("required").add("action");
		return schema;
	}

	private static JsonNode inventoryClickSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("slot").put("type", "integer").put("description", "Inventory slot index");
		props.putObject("button").put("type", "integer").put("description", "Mouse button: 0=left, 1=right, 2=middle");
		props.putObject("mode").put("type", "string").put("description", "Click mode: pickup, quick_move, swap, clone, throw, quick_craft, pickup_all");
		schema.putArray("required").add("slot");
		return schema;
	}

	private static JsonNode screenActionSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("action").put("type", "string").put("description", "Action: click, close, type");
		props.putObject("widgetId").put("type", "integer").put("description", "Widget index");
		props.putObject("label").put("type", "string").put("description", "Widget text label");
		props.putObject("text").put("type", "string").put("description", "Text to type");
		schema.putArray("required").add("action");
		return schema;
	}

	private static JsonNode chatSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("message").put("type", "string").put("description", "Chat message or command to send");
		props.putObject("read").put("type", "boolean").put("description", "Set to true to read recent chat history");
		props.putObject("limit").put("type", "integer").put("description", "Maximum messages to read");
		return schema;
	}

	private static JsonNode baritoneGotoSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("x").put("type", "integer");
		props.putObject("y").put("type", "integer");
		props.putObject("z").put("type", "integer");
		props.putObject("block").put("type", "string");
		return schema;
	}

	private static JsonNode baritoneMineSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("block").put("type", "string").put("description", "Block identifier e.g. 'minecraft:iron_ore'");
		props.putObject("x").put("type", "integer");
		props.putObject("y").put("type", "integer");
		props.putObject("z").put("type", "integer");
		props.putObject("limit").put("type", "integer");
		return schema;
	}

	private static JsonNode baritoneFollowSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("entity").put("type", "string").put("description", "Player username or entity name/UUID");
		props.putObject("entityType").put("type", "string").put("description", "Entity type e.g. 'cow' or 'zombie'");
		return schema;
	}

	private static JsonNode playerSkinSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("player").put("type", "string").put("description", "Optional player username or UUID (defaults to local player)");
		return schema;
	}

	private static JsonNode gameTexturesSchema() {
		ObjectNode schema = Json.MAPPER.createObjectNode();
		schema.put("type", "object");
		ObjectNode props = schema.putObject("properties");
		props.putObject("id").put("type", "string").put("description", "Atlas identifier or alias ('blocks', 'particles', 'gui'). Defaults to 'blocks'");
		props.putObject("embed_image").put("type", "boolean").put("description", "Whether to return the stitched atlas PNG image block");
		return schema;
	}
}
