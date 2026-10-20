package dev.advik.mcdt.api.v1;

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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ObjectInvokeHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;
	private final HandleTable handleTable;
	private final ReflectionCache cache;
	private final ReflectionSerializer serializer;

	public ObjectInvokeHandler(
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
			path = "/v1/objects/{handle}/invoke",
			methods = {HttpMethod.POST},
			summary = "Invoke an object method",
			description = "Calls a method on an object handle or named root with JSON arguments. Requires reflect-write permission.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "handle", description = "Object handle or root name", required = true)
			},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = InvokeRequest.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Method invoked successfully", content = {@OpenApiContent(from = InvokeResponse.class)}),
					@OpenApiResponse(status = "400", description = "Invalid arguments or invocation failed", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Permission denied or denylisted class", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "404", description = "Handle or method not found", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "410", description = "Object collected", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		String handle = ctx.pathParam("handle");

		InvokeRequest request;
		try {
			request = ctx.bodyAsClass(InvokeRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Invalid request body: " + e.getMessage());
		}

		if (request.method() == null || request.method().isBlank()) {
			throw Problems.badRequest("Missing required 'method' name");
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
			List<ReflectionCache.MethodMetadata> candidates = metadata.getMethods(request.method());
			if (candidates.isEmpty()) {
				throw Problems.methodNotFound(request.method(), metadata.resolvedClassName());
			}

			List<Object> args = request.args();
			ReflectionCache.MethodMetadata chosen = null;
			for (ReflectionCache.MethodMetadata m : candidates) {
				if (m.method().getParameterCount() == args.size()) {
					chosen = m;
					break;
				}
			}

			if (chosen == null) {
				throw Problems.methodNotFound(request.method() + "(" + args.size() + " args)", metadata.resolvedClassName());
			}

			Method method = chosen.method();
			Class<?>[] paramTypes = method.getParameterTypes();
			Object[] convertedArgs = new Object[paramTypes.length];
			for (int i = 0; i < paramTypes.length; i++) {
				convertedArgs[i] = TypeConverter.convert(args.get(i), paramTypes[i], handleTable);
			}

			Object result;
			try {
				result = chosen.invoke(target, convertedArgs);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				throw Problems.invokeFailed(chosen.resolvedName(), cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
			} catch (Throwable t) {
				throw Problems.invokeFailed(chosen.resolvedName(), t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
			}

			if (ReflectionSecurity.isSensitiveMethod(clazz, chosen.resolvedName())) {
				result = ReflectionSecurity.REDACTED;
			}

			Object serializedResult = null;
			if (method.getReturnType() != void.class) {
				serializedResult = serializer.serialize(
						result,
						0,
						1,
						Collections.newSetFromMap(new IdentityHashMap<>()),
						null,
						null
				);
			}

			InvokeResponse response = new InvokeResponse(chosen.resolvedName(), chosen.returnType(), serializedResult);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(res -> {
			ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
			ctx.contentType("application/json");
			ctx.result(res.body().toString());
		});

		ctx.future(() -> future);
	}
}
