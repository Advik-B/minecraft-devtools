package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.util.List;

/**
 * DTO for /v1/advancements.
 */
public final class AdvancementsDto {
	private AdvancementsDto() {}

	@OpenApiName("AdvancementInfo")
	public record AdvancementInfo(
			String id,
			String title,
			String description,
			String type,
			String icon,
			String parent,
			boolean done,
			float percent,
			List<String> completedCriteria,
			List<String> remainingCriteria
	) {}

	@OpenApiName("AdvancementsResponse")
	public record Response(
			int total,
			int completed,
			List<AdvancementInfo> advancements
	) {}
}
