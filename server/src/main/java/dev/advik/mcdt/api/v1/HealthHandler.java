package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TaskStats;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Handles {@code GET /v1/health}. Dispatches through the {@link TaskQueue} onto the client thread.
 */
public final class HealthHandler implements Handler {
	private final McdtEnvironment env;
	private final McdtConfig config;
	private final TaskQueue queue;

	public HealthHandler(McdtEnvironment env, McdtConfig config, TaskQueue queue) {
		this.env = env;
		this.config = config;
		this.queue = queue;
	}

	@OpenApi(
			path = "/v1/health",
			methods = {HttpMethod.GET},
			summary = "Mod and runtime health",
			description = "Returns mod version, Minecraft version, uptime, client tick, queue metrics, and Baritone status.",
			tags = {"System"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "System is healthy",
							content = {@OpenApiContent(from = HealthResponse.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized - missing or invalid token",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "503",
							description = "Service Unavailable - task queue is full",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "504",
							description = "Gateway Timeout - client tick did not process task within timeout",
							content = {@OpenApiContent(from = Problem.class)}
					)
			}
	)
	@Override
	public void handle(Context ctx) {
		long timeoutMs = config.requestTimeoutMs;

		CompletableFuture<Void> future = queue.submit(() -> {
			long uptimeMs = Math.max(0, Duration.between(env.startTime(), Instant.now()).toMillis());
			TaskStats.Snapshot snap = queue.stats().snapshot();
			HealthResponse.QueueStats queueStats = new HealthResponse.QueueStats(
					queue.depth(),
					queue.capacity(),
					snap.avgMicros(),
					snap.p99Micros()
			);

			HealthResponse response = new HealthResponse(
					env.mcdtVersion(),
					env.gameVersion(),
					uptimeMs,
					queue.clock().current(),
					queueStats,
					env.baritone(),
					config.permission.id(),
					dev.advik.mcdt.events.EventHub.getInstance().totalDroppedEvents()
			);

			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
