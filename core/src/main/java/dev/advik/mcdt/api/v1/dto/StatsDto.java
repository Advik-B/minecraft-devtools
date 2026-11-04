package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.util.Map;

/**
 * DTO for /v1/stats.
 */
public final class StatsDto {
	private StatsDto() {}

	@OpenApiName("StatsResponse")
	public record Response(
			Map<String, Integer> custom,
			Map<String, Integer> mined,
			Map<String, Integer> crafted,
			Map<String, Integer> used,
			Map<String, Integer> broken,
			Map<String, Integer> killed,
			Map<String, Integer> killedBy
	) {}
}
