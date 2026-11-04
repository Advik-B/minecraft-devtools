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
import dev.advik.mcdt.reflection.ReflectionCache;
import dev.advik.mcdt.reflection.ReflectionSecurity;
import dev.advik.mcdt.reflection.ReflectionSerializer;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ObjectFieldHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final HandleTable handleTable;
	private final ReflectionCache cache;
	private final ReflectionSerializer serializer;

	public ObjectFieldHandler(
			McdtConfig config,
			TaskQueue queue,
			GameClient gameClient,
			HandleTable handleTable,
			ReflectionCache cache,
			ReflectionSerializer serializer
	) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
		this.handleTable = handleTable;
		this.cache = cache;
		this.serializer = serializer;
	}

	@OpenApi(
			path = "/v1/objects/{handle}/fields/{name}",
			methods = {HttpMethod.GET},
			summary = "Inspect a specific field",
			description = "Returns one field expanded with depth limiting on an object handle or named root.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "handle", description = "Object handle or root name", required = true),
					@OpenApiParam(name = "name", description = "Field name", required = true)
			},
			queryParams = {
					@OpenApiParam(name = "depth", type = Integer.class, description = "Depth limit to expand the field value (default 1)")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Expanded field",
							content = {@OpenApiContent(from = FieldInspectResponse.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "404",
							description = "Handle or field not found",
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
		String fieldName = ctx.pathParam("name");
		int depth = ctx.queryParamAsClass("depth", Integer.class).getOrDefault(ReflectionSerializer.DEFAULT_MAX_DEPTH);
		if (depth < 0) {
			throw Problems.invalidParameter("depth", "Depth must be greater than or equal to 0.");
		}
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

			Class<?> clazz = target.getClass();
			ReflectionSecurity.checkClass(clazz);
			ReflectionCache.ClassMetadata metadata = cache.getMetadata(clazz);

			ReflectionCache.FieldMetadata field = metadata.getField(fieldName);
			if (field == null) {
				throw Problems.fieldNotFound(fieldName, metadata.resolvedClassName());
			}

			Object val;
			if (ReflectionSecurity.isSensitiveField(clazz, field.resolvedName())) {
				val = ReflectionSecurity.REDACTED;
			} else {
				try {
					Object rawVal = field.getValue(target);
					Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
					val = serializer.serialize(rawVal, 0, depth, visited, null, null);
				} catch (Throwable t) {
					val = "<unreadable: " + t.getMessage() + ">";
				}
			}

			FieldInspectResponse resp = new FieldInspectResponse(
					field.resolvedName(),
					metadata.resolvedClassName(),
					field.typeName(),
					val
			);
			return Json.MAPPER.valueToTree(resp);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
