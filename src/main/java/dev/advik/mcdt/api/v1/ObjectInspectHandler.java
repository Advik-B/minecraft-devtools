package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ReflectionDto.*;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TaskResult;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.reflection.HandleTable;
import dev.advik.mcdt.reflection.NameService;
import dev.advik.mcdt.reflection.PathEvaluator;
import dev.advik.mcdt.reflection.ReflectionSerializer;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ObjectInspectHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final HandleTable handleTable;
	private final PathEvaluator pathEvaluator;
	private final ReflectionSerializer serializer;

	public ObjectInspectHandler(
			McdtConfig config,
			TaskQueue queue,
			GameClient gameClient,
			HandleTable handleTable,
			PathEvaluator pathEvaluator,
			ReflectionSerializer serializer
	) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
		this.handleTable = handleTable;
		this.pathEvaluator = pathEvaluator;
		this.serializer = serializer;
	}

	@OpenApi(
			path = "/v1/objects/{handle}",
			methods = {HttpMethod.GET},
			summary = "Inspect an object",
			description = "Returns class, depth-limited fields, and methods for a handle or named root. Supports ?path= traversal queries.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "handle", description = "Object handle or root name", required = true)
			},
			queryParams = {
					@OpenApiParam(name = "path", description = "Dot/bracket path traversal query (e.g. player.getInventory().items[0])"),
					@OpenApiParam(name = "depth", type = Integer.class, description = "Depth limit for child fields (default 1)"),
					@OpenApiParam(name = "offset", type = Integer.class, description = "Collection pagination offset"),
					@OpenApiParam(name = "limit", type = Integer.class, description = "Collection pagination limit (capped at 64)")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Inspected object or path evaluation result",
							content = {@OpenApiContent(from = ObjectInspectResponse.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Invalid path or query parameter",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "404",
							description = "Handle not found or expired",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "410",
							description = "Object garbage collected",
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
		String handle = ctx.pathParam("handle");
		String path = ctx.queryParam("path");
		int depth = ctx.queryParamAsClass("depth", Integer.class).getOrDefault(ReflectionSerializer.DEFAULT_MAX_DEPTH);
		if (depth < 0) {
			throw Problems.invalidParameter("depth", "Depth must be greater than or equal to 0.");
		}
		Integer offset = ctx.queryParam("offset") != null ? ctx.queryParamAsClass("offset", Integer.class).get() : null;
		Integer limit = ctx.queryParam("limit") != null ? ctx.queryParamAsClass("limit", Integer.class).get() : null;
		long timeoutMs = config.requestTimeoutMs;

		CompletableFuture<Void> future = queue.submit(() -> {
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
					return Json.MAPPER.valueToTree(Map.of("path", path, "value", "null"));
				}

				// If evaluated object is a primitive or adapter type, return path and value
				if (isPrimitiveOrAdapter(evaluated)) {
					Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
					Object val = serializer.serialize(evaluated, 0, depth, visited, offset, limit);
					return Json.MAPPER.valueToTree(Map.of("path", path, "value", val));
				}

				String subHandle = handleTable.getOrCreateHandle(evaluated);
				Map<String, Object> inspected = serializer.inspectObject(evaluated, subHandle, depth, offset, limit);
				inspected.put("path", path);
				return Json.MAPPER.valueToTree(inspected);
			}

			return Json.MAPPER.valueToTree(serializer.inspectObject(target, handle, depth, offset, limit));
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	private static boolean isPrimitiveOrAdapter(Object obj) {
		if (obj == null || obj instanceof Number || obj instanceof Boolean || obj instanceof Character || obj instanceof String || obj instanceof Enum<?>) {
			return true;
		}
		String name = NameService.getInstance().resolveClassName(obj.getClass().getName());
		return name.startsWith("net.minecraft.world.phys.Vec3")
				|| name.startsWith("net.minecraft.core.BlockPos")
				|| name.startsWith("net.minecraft.core.Vec3i")
				|| name.startsWith("net.minecraft.resources.ResourceLocation")
				|| name.startsWith("net.minecraft.network.chat.Component")
				|| name.startsWith("net.minecraft.world.item.ItemStack")
				|| name.startsWith("net.minecraft.world.level.block.state.BlockState")
				|| name.equals("java.util.UUID");
	}
}
