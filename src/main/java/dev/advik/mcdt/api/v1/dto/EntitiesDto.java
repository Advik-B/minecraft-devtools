package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record EntitiesDto(
		double[] center,
		double radius,
		int count,
		List<EntityInfo> entities
) {
	public record EntityInfo(
			int id,
			String uuid,
			String type,
			double[] pos,
			double[] velocity,
			float yaw,
			float pitch,
			double distance,
			boolean alive,
			@JsonInclude(JsonInclude.Include.NON_NULL) Float health,
			@JsonInclude(JsonInclude.Include.NON_NULL) Float maxHealth,
			@JsonInclude(JsonInclude.Include.NON_NULL) String customName,
			BoundingBox boundingBox
	) {}

	public record BoundingBox(
			double[] min,
			double[] max
	) {}
}
