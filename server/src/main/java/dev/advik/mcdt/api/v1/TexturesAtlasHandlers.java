package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.TextureAtlasDto;
import dev.advik.mcdt.api.v1.dto.TextureAtlasesDto;
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

public final class TexturesAtlasHandlers {
	private TexturesAtlasHandlers() {}

	public static final class AtlasesHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final GameClient gameClient;

		public AtlasesHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
			this.config = config;
			this.queue = queue;
			this.gameClient = gameClient;
		}

		@OpenApi(
				path = "/v1/textures/atlases",
				methods = {HttpMethod.GET},
				summary = "List loaded texture atlases",
				description = "Returns a summary list of all currently loaded texture atlases in the game client (e.g. blocks, particles, gui).",
				tags = {"Textures"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				responses = {
						@OpenApiResponse(
								status = "200",
								description = "List of loaded texture atlases",
								content = {@OpenApiContent(type = "application/json", from = TextureAtlasesDto.class)}
						),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "500", description = "Extraction error", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				TextureAtlasesDto result = gameClient.getLoadedAtlases();
				return Json.MAPPER.valueToTree(result);
			}, timeoutMs).thenAccept(result -> {
				ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
				ctx.contentType("application/json");
				ctx.result(result.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class AtlasHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final GameClient gameClient;

		public AtlasHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
			this.config = config;
			this.queue = queue;
			this.gameClient = gameClient;
		}

		@OpenApi(
				path = "/v1/textures/atlas",
				methods = {HttpMethod.GET},
				summary = "Get texture atlas details and sprite UV mapping",
				description = "Returns metadata and sprite UV coordinates for a texture atlas (defaulting to the blocks atlas). Includes pixel bounds, normalized UVs (0.0-1.0), and animation metadata.",
				tags = {"Textures"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				queryParams = {
						@OpenApiParam(name = "id", description = "Atlas identifier or alias (e.g. 'blocks', 'particles', 'gui', or 'minecraft:textures/atlas/blocks.png'). Defaults to 'blocks'."),
						@OpenApiParam(name = "embed_image", description = "Whether to include the full stitched texture encoded as a base64 PNG string.")
				},
				responses = {
						@OpenApiResponse(
								status = "200",
								description = "Atlas metadata and sprite UV map",
								content = {@OpenApiContent(type = "application/json", from = TextureAtlasDto.class)}
						),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "404", description = "Atlas not found", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "500", description = "Extraction error", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			String atlasId = ctx.queryParam("id");
			boolean embedImage = Boolean.parseBoolean(ctx.queryParam("embed_image"));
			long timeoutMs = config.requestTimeoutMs;

			CompletableFuture<Void> future = queue.submit(() -> {
				TextureAtlasDto result = gameClient.getAtlasDetails(atlasId, embedImage);
				return Json.MAPPER.valueToTree(result);
			}, timeoutMs).thenAccept(result -> {
				ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
				ctx.contentType("application/json");
				ctx.result(result.body().toString());
			});
			ctx.future(() -> future);
		}
	}

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
				path = "/v1/textures/atlas/image",
				methods = {HttpMethod.GET},
				summary = "Get stitched texture atlas image",
				description = "Streams the full stitched texture atlas as raw PNG bytes (image/png). Defaults to the blocks atlas.",
				tags = {"Textures"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				queryParams = {
						@OpenApiParam(name = "id", description = "Atlas identifier or alias (defaults to 'blocks')")
				},
				responses = {
						@OpenApiResponse(
								status = "200",
								description = "Stitched atlas PNG image bytes",
								content = {@OpenApiContent(type = "image/png")}
						),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "404", description = "Atlas not found", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "500", description = "Texture extraction error", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			String atlasId = ctx.queryParam("id");
			long timeoutMs = config.requestTimeoutMs;

			CompletableFuture<byte[]> future = new CompletableFuture<>();
			try {
				queue.submit(() -> {
					try {
						byte[] pngBytes = gameClient.getAtlasImage(atlasId);
						future.complete(pngBytes);
					} catch (Throwable t) {
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

			ctx.future(() -> future.thenAccept(bytes -> {
				ctx.contentType("image/png");
				ctx.result(bytes);
			}));
		}
	}
}
