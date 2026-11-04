package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.HudDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for GET /v1/hud.
 */
public final class HudHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public HudHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/hud",
			methods = {HttpMethod.GET},
			summary = "Read HUD telemetry and overlays",
			description = "Returns current action bar, titles, boss bars, scoreboard sidebar, tab list, subtitles, held items, and active toasts.",
			tags = {"Player"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "include", description = "Comma-separated filter for HUD components: actionbar, title, bossbars, scoreboard, tablist, subtitles, helditem, toasts. Omit to include all.")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Current HUD state",
							content = {@OpenApiContent(from = HudDto.Response.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "409",
							description = "No player loaded",
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
		String includeParam = ctx.queryParam("include");
		Set<String> include = null;
		if (includeParam != null && !includeParam.isBlank()) {
			include = new HashSet<>();
			for (String part : includeParam.split(",")) {
				include.add(part.trim().toLowerCase());
			}
		}

		final Set<String> finalInclude = include;
		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			HudDto.Response response = gameClient.getHud(finalInclude);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}
}
