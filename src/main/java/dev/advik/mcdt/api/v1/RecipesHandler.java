package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.RecipesDto;
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
 * Handler for GET /v1/recipes and POST /v1/recipes/place.
 */
public final class RecipesHandler implements Handler {
	private final McdtConfig config;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public RecipesHandler(McdtConfig config, TaskQueue queue, GameClient gameClient) {
		this.config = config;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	@OpenApi(
			path = "/v1/recipes",
			methods = {HttpMethod.GET},
			summary = "Read unlocked recipes",
			description = "Returns unlocked recipe book entries with result items, categories, craftable status, and ingredient requirements.",
			tags = {"Inventory"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			queryParams = {
					@OpenApiParam(name = "craftable", type = Boolean.class, description = "Filter by craftable status based on current inventory"),
					@OpenApiParam(name = "category", description = "Filter by recipe book category"),
					@OpenApiParam(name = "search", description = "Filter recipes by result item ID")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "List of recipe entries",
							content = {@OpenApiContent(from = RecipesDto.Response.class)}
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
	public void get(Context ctx) {
		String craftableStr = ctx.queryParam("craftable");
		Boolean craftable = craftableStr != null ? Boolean.parseBoolean(craftableStr) : null;
		String category = ctx.queryParam("category");
		String search = ctx.queryParam("search");

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			RecipesDto.Response response = gameClient.getRecipes(craftable, category, search);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@OpenApi(
			path = "/v1/recipes/place",
			methods = {HttpMethod.POST},
			summary = "Place recipe into active crafting container",
			description = "Transfers ingredient items from the player's inventory into the open crafting grid or furnace recipe slot.",
			tags = {"Inventory"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(
					required = true,
					content = {@OpenApiContent(from = RecipesDto.PlaceRequest.class)}
			),
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Recipe placed into container",
							content = {@OpenApiContent(from = RecipesDto.PlaceResponse.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Bad request",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "403",
							description = "Permission denied",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "409",
							description = "No player or container open",
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
	public void place(Context ctx) {
		RecipesDto.PlaceRequest request;
		try {
			request = ctx.bodyAsClass(RecipesDto.PlaceRequest.class);
		} catch (Exception e) {
			throw Problems.badRequest("Malformed JSON body for recipe place request");
		}

		long timeoutMs = config.requestTimeoutMs;
		CompletableFuture<Void> future = queue.submit(() -> {
			RecipesDto.PlaceResponse response = gameClient.placeRecipe(request);
			return Json.MAPPER.valueToTree(response);
		}, timeoutMs).thenAccept(result -> {
			ctx.header("X-MCDT-Tick", String.valueOf(result.tick()));
			ctx.contentType("application/json");
			ctx.result(result.body().toString());
		});

		ctx.future(() -> future);
	}

	@Override
	public void handle(Context ctx) {
		if ("GET".equalsIgnoreCase(ctx.method().name())) {
			get(ctx);
		} else {
			place(ctx);
		}
	}
}
