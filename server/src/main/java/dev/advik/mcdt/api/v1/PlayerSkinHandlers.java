package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.PlayerSkinDto;
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

import java.util.concurrent.CompletableFuture;

public final class PlayerSkinHandlers {
	private PlayerSkinHandlers() {}

	public static final class ImageHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final GameClient gameClient;

		public ImageHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
			this.config = config;
			this.queue = queue;
			this.gameClient = gameClient;
		}

		@OpenApi(
				path = "/v1/player/skin",
				methods = {HttpMethod.GET},
				summary = "Get player skin image",
				description = "Returns the player skin as a raw PNG image (image/png) with X-Skin-Model (wide or slim) and X-Player-Name response headers. Supports format=json query parameter for JSON metadata.",
				tags = {"Player"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				queryParams = {
						@OpenApiParam(name = "player", description = "Optional player username or UUID (defaults to local player)"),
						@OpenApiParam(name = "format", description = "Response format: 'png' (default) or 'json'")
				},
				responses = {
						@OpenApiResponse(
								status = "200",
								description = "PNG skin image bytes or JSON metadata",
								content = {
										@OpenApiContent(type = "image/png"),
										@OpenApiContent(type = "application/json", from = PlayerSkinDto.class)
								}
						),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "404", description = "Target player not found", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "409", description = "No player loaded", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "500", description = "Texture extraction error", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			String targetPlayer = ctx.queryParam("player");
			String format = ctx.queryParam("format");
			boolean asJson = "json".equalsIgnoreCase(format);
			long timeoutMs = config.requestTimeoutMs;

			if (asJson) {
				CompletableFuture<Void> future = queue.submit(() -> {
					PlayerSkinDto skinInfo = gameClient.getPlayerSkinInfo(targetPlayer, false);
					return Json.MAPPER.valueToTree(skinInfo);
				}, timeoutMs).thenAccept(result -> {
					ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
					ctx.contentType("application/json");
					ctx.result(result.body().toString());
				});
				ctx.future(() -> future);
			} else {
				CompletableFuture<byte[]> future = new CompletableFuture<>();
				CompletableFuture<PlayerSkinDto> infoFuture = new CompletableFuture<>();

				try {
					queue.submit(() -> {
						try {
							PlayerSkinDto skinInfo = gameClient.getPlayerSkinInfo(targetPlayer, false);
							byte[] pngBytes = gameClient.getPlayerSkinImage(targetPlayer);
							infoFuture.complete(skinInfo);
							future.complete(pngBytes);
						} catch (Throwable t) {
							infoFuture.completeExceptionally(t);
							future.completeExceptionally(t);
						}
						return Json.MAPPER.nullNode();
					}, timeoutMs).exceptionally(ex -> {
						future.completeExceptionally(ex);
						return null;
					});
				} catch (ProblemException e) {
					throw e;
				} catch (Exception e) {
					throw Problems.queueFull();
				}

				ctx.future(() -> future.thenAcceptBoth(infoFuture, (bytes, info) -> {
					if (info != null) {
						ctx.header("X-Skin-Model", info.model());
						ctx.header("X-Player-Name", info.playerName());
					}
					ctx.contentType("image/png");
					ctx.result(bytes);
				}));
			}
		}
	}

	public static final class InfoHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final GameClient gameClient;

		public InfoHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
			this.config = config;
			this.queue = queue;
			this.gameClient = gameClient;
		}

		@OpenApi(
				path = "/v1/player/skin/info",
				methods = {HttpMethod.GET},
				summary = "Get player skin metadata",
				description = "Returns player skin metadata including model type (wide or slim), texture URLs, and optional base64 image data.",
				tags = {"Player"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				queryParams = {
						@OpenApiParam(name = "player", description = "Optional player username or UUID (defaults to local player)"),
						@OpenApiParam(name = "embed_image", description = "Whether to include base64-encoded PNG image data in the response")
				},
				responses = {
						@OpenApiResponse(
								status = "200",
								description = "Player skin metadata",
								content = {@OpenApiContent(type = "application/json", from = PlayerSkinDto.class)}
						),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "404", description = "Target player not found", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "409", description = "No player loaded", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "500", description = "Texture extraction error", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			String targetPlayer = ctx.queryParam("player");
			boolean embedImage = Boolean.parseBoolean(ctx.queryParam("embed_image"));
			long timeoutMs = config.requestTimeoutMs;

			CompletableFuture<Void> future = queue.submit(() -> {
				PlayerSkinDto skinInfo = gameClient.getPlayerSkinInfo(targetPlayer, embedImage);
				return Json.MAPPER.valueToTree(skinInfo);
			}, timeoutMs).thenAccept(result -> {
				ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
				ctx.contentType("application/json");
				ctx.result(result.body().toString());
			});
			ctx.future(() -> future);
		}
	}
}
