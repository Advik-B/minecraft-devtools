package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ViewBlocksDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.concurrent.CompletableFuture;

/**
 * Handlers for structured view endpoints /v1/view/blocks and /v1/view/blocks/summary.
 */
public final class ViewBlocksHandlers {
	private ViewBlocksHandlers() {}

	public static final class ViewHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final GameClient gameClient;

		public ViewHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
			this.config = config;
			this.queue = queue;
			this.gameClient = gameClient;
		}

		@OpenApi(
				path = "/v1/view/blocks",
				methods = {HttpMethod.GET},
				summary = "Visible blocks from camera view",
				description = "Rays cast through view frustum returning visible palette-indexed blocks, faces, distance, and screen coverage.",
				tags = {"World"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				queryParams = {
						@OpenApiParam(name = "res", description = "Ray grid size (e.g. 128x72, up to 512x288). Default: 128x72"),
						@OpenApiParam(name = "maxDistance", description = "Maximum ray distance in blocks (default 64)"),
						@OpenApiParam(name = "fluids", description = "Record fluid surfaces (default true)"),
						@OpenApiParam(name = "passThrough", description = "Record transparent blocks and continue ray (default true)"),
						@OpenApiParam(name = "entities", description = "Record entity hits (default false)"),
						@OpenApiParam(name = "grid", description = "Include 2D labels and depth arrays (default false)"),
						@OpenApiParam(name = "minCoverage", description = "Minimum screen coverage fraction to include block (default 0.0)")
				},
				responses = {
						@OpenApiResponse(
								status = "200",
								description = "Structured block visibility map",
								content = {@OpenApiContent(from = ViewBlocksDto.Response.class)}
						),
						@OpenApiResponse(
								status = "400",
								description = "Invalid parameter",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "401",
								description = "Unauthorized",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "403",
								description = "View blocks disabled",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "409",
								description = "No player or world loaded",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "503",
								description = "Queue full",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "504",
								description = "Timeout",
								content = {@OpenApiContent(from = Problem.class)}
						)
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!config.enableViewBlocks) {
				throw Problems.viewBlocksDisabled();
			}

			String res = ctx.queryParam("res");
			Double maxDistance = null;
			if (ctx.queryParam("maxDistance") != null && !ctx.queryParam("maxDistance").isBlank()) {
				try {
					maxDistance = Double.parseDouble(ctx.queryParam("maxDistance"));
				} catch (NumberFormatException e) {
					throw Problems.invalidParameter("maxDistance", "Must be a valid number");
				}
			}

			boolean fluids = ctx.queryParam("fluids") == null || Boolean.parseBoolean(ctx.queryParam("fluids"));
			boolean passThrough = ctx.queryParam("passThrough") == null || Boolean.parseBoolean(ctx.queryParam("passThrough"));
			boolean entities = Boolean.parseBoolean(ctx.queryParam("entities"));
			boolean grid = Boolean.parseBoolean(ctx.queryParam("grid"));
			double minCoverage = 0.0;
			if (ctx.queryParam("minCoverage") != null && !ctx.queryParam("minCoverage").isBlank()) {
				try {
					minCoverage = Double.parseDouble(ctx.queryParam("minCoverage"));
				} catch (NumberFormatException e) {
					throw Problems.invalidParameter("minCoverage", "Must be a valid number");
				}
			}

			long timeoutMs = config.requestTimeoutMs;
			final Double finalMaxDist = maxDistance;
			final double finalMinCoverage = minCoverage;
			CompletableFuture<Void> future = queue.submit(() -> {
				ViewBlocksDto.Response response = gameClient.getViewBlocks(
						res,
						finalMaxDist,
						fluids,
						passThrough,
						entities,
						grid,
						finalMinCoverage
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

	public static final class SummaryHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final GameClient gameClient;

		public SummaryHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
			this.config = config;
			this.queue = queue;
			this.gameClient = gameClient;
		}

		@OpenApi(
				path = "/v1/view/blocks/summary",
				methods = {HttpMethod.GET},
				summary = "Visible blocks summary overview",
				description = "Returns block counts, nearest block instances, and crosshair hit result.",
				tags = {"World"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				queryParams = {
						@OpenApiParam(name = "maxDistance", description = "Maximum ray distance in blocks (default 64)")
				},
				responses = {
						@OpenApiResponse(
								status = "200",
								description = "Block summary overview",
								content = {@OpenApiContent(from = ViewBlocksDto.SummaryResponse.class)}
						),
						@OpenApiResponse(
								status = "401",
								description = "Unauthorized",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "403",
								description = "View blocks disabled",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "409",
								description = "No player or world loaded",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "503",
								description = "Queue full",
								content = {@OpenApiContent(from = Problem.class)}
						),
						@OpenApiResponse(
								status = "504",
								description = "Timeout",
								content = {@OpenApiContent(from = Problem.class)}
						)
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!config.enableViewBlocks) {
				throw Problems.viewBlocksDisabled();
			}

			Double maxDistance = null;
			if (ctx.queryParam("maxDistance") != null && !ctx.queryParam("maxDistance").isBlank()) {
				try {
					maxDistance = Double.parseDouble(ctx.queryParam("maxDistance"));
				} catch (NumberFormatException e) {
					throw Problems.invalidParameter("maxDistance", "Must be a valid number");
				}
			}

			long timeoutMs = config.requestTimeoutMs;
			final Double finalMaxDist = maxDistance;
			CompletableFuture<Void> future = queue.submit(() -> {
				ViewBlocksDto.SummaryResponse response = gameClient.getViewBlocksSummary(finalMaxDist);
				return Json.MAPPER.valueToTree(response);
			}, timeoutMs).thenAccept(result -> {
				ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
				ctx.contentType("application/json");
				ctx.result(result.body().toString());
			});

			ctx.future(() -> future);
		}
	}
}
