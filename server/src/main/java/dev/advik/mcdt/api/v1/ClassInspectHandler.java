package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ReflectionDto.*;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TaskResult;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.reflection.NameService;
import dev.advik.mcdt.reflection.ReflectionSecurity;
import dev.advik.mcdt.reflection.ReflectionSerializer;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ClassInspectHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final NameService nameService;
	private final ReflectionSerializer serializer;

	public ClassInspectHandler(McdtConfig config, TaskQueue queue, NameService nameService, ReflectionSerializer serializer) {
		this.config = config;
		this.queue = queue;
		this.nameService = nameService;
		this.serializer = serializer;
	}

	@OpenApi(
			path = "/v1/classes/{name}",
			methods = {HttpMethod.GET},
			summary = "Inspect static class members",
			description = "Returns static fields and static methods of a class.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "name", description = "Fully-qualified class name", required = true)
			},
			queryParams = {
					@OpenApiParam(name = "depth", type = Integer.class, description = "Depth limit to expand static field values (default 1)")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Class static inspection",
							content = {@OpenApiContent(from = ClassInspectResponse.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "403",
							description = "Class blocked by security denylist",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "404",
							description = "Class not found",
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
		String className = ctx.pathParam("name");
		int depth = ctx.queryParamAsClass("depth", Integer.class).getOrDefault(ReflectionSerializer.DEFAULT_MAX_DEPTH);
		if (depth < 0) {
			throw Problems.invalidParameter("depth", "Depth must be greater than or equal to 0.");
		}

		ReflectionSecurity.checkClassName(className);
		String runtimeName = nameService.toRuntimeClassName(className);

		Class<?> clazz;
		try {
			clazz = Class.forName(runtimeName, false, Thread.currentThread().getContextClassLoader());
		} catch (ClassNotFoundException e) {
			try {
				clazz = Class.forName(runtimeName);
			} catch (ClassNotFoundException e2) {
				throw Problems.classNotFound(className);
			}
		}

		ReflectionSecurity.checkClass(clazz);

		final Class<?> targetClass = clazz;
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(
				() -> Json.MAPPER.valueToTree(serializer.inspectClass(targetClass, depth)),
				timeoutMs
		).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
