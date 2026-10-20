package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.util.List;

/**
 * DTO for /v1/recipes.
 */
public final class RecipesDto {
	private RecipesDto() {}

	@OpenApiName("RecipeResultItem")
	public record ResultItem(
			String id,
			int count
	) {}

	@OpenApiName("RecipeInfo")
	public record RecipeInfo(
			int id,
			String category,
			ResultItem result,
			boolean craftable,
			List<String> ingredients
	) {}

	@OpenApiName("RecipesResponse")
	public record Response(
			int total,
			List<RecipeInfo> recipes
	) {}

	@OpenApiName("RecipePlaceRequest")
	public record PlaceRequest(
			int recipeId,
			Boolean useMaxItems
	) {}

	@OpenApiName("RecipePlaceResponse")
	public record PlaceResponse(
			boolean success,
			int recipeId,
			int containerId
	) {}
}
