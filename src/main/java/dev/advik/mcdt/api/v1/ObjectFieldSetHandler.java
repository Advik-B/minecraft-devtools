package dev.advik.mcdt.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.api.v1.dto.ReflectionWriteDto.*;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.reflection.*;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;

public final class ObjectFieldSetHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final HandleTable handleTable;
	private final ReflectionCache cache;
	private final ReflectionSerializer serializer;

	public ObjectFieldSetHandler(
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
			methods = {HttpMethod.PUT},
			summary = "Set a field value",
			description = "Mutates a field on an object handle or named root. Requires reflect-write permission.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "handle", description = "Object handle or root name", required = true),
					@OpenApiParam(name = "name", description = "Field name", required = true)
			},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = FieldPutRequest.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Field updated", content = {@OpenApiContent(from = FieldPutResponse.class)}),
					@OpenApiResponse(status = "400", description = "Invalid value or field is final", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied or sensitive field", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "404", description = "Handle or field not found", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "410", description = "Object collected", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		String handle = ctx.pathParam("handle");
		String fieldName = ctx.pathParam("name");

		JsonNode bodyNode;
		try {
			bodyNode = Json.MAPPER.readTree(ctx.body());
		} catch (Exception e) {
			throw Problems.badRequest("Invalid JSON body: " + e.getMessage());
		}

		Object rawValue = bodyNode;
		if (bodyNode != null && bodyNode.isObject() && bodyNode.has("value")) {
			rawValue = bodyNode.get("value");
		}

		final Object inputValue = rawValue;
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

			if (ReflectionSecurity.isSensitiveField(clazz, fieldName)) {
				throw Problems.reflectionDenied("Field '" + fieldName + "' contains sensitive credentials and cannot be modified.");
			}

			ReflectionCache.ClassMetadata metadata = cache.getMetadata(clazz);
			ReflectionCache.FieldMetadata fieldMeta = metadata.getField(fieldName);
			if (fieldMeta == null) {
				throw Problems.fieldNotFound(fieldName, metadata.resolvedClassName());
			}

			if (fieldMeta.isFinal()) {
				throw Problems.badRequest("Field '" + fieldMeta.resolvedName() + "' on class '"
						+ metadata.resolvedClassName() + "' is final and cannot be modified.");
			}

			Object converted = TypeConverter.convert(inputValue, fieldMeta.field().getType(), handleTable);

			try {
				fieldMeta.field().set(target, converted);
			} catch (Exception e) {
				throw Problems.badRequest("Failed to set field '" + fieldMeta.resolvedName() + "': " + e.getMessage());
			}

			Object serializedValue = serializer.serialize(
					converted,
					0,
					1,
					Collections.newSetFromMap(new IdentityHashMap<>()),
					null,
					null
			);

			FieldPutResponse response = new FieldPutResponse(true, fieldMeta.resolvedName(), serializedValue);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
