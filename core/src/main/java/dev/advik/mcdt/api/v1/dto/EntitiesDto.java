package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

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
			BoundingBox boundingBox,
			@JsonInclude(JsonInclude.Include.NON_NULL) String category
	) {
		public EntityInfo(
				int id,
				String uuid,
				String type,
				double[] pos,
				double[] velocity,
				float yaw,
				float pitch,
				double distance,
				boolean alive,
				Float health,
				Float maxHealth,
				String customName,
				BoundingBox boundingBox
		) {
			this(id, uuid, type, pos, velocity, yaw, pitch, distance, alive, health, maxHealth, customName, boundingBox, null);
		}
	}

	public record BoundingBox(
			double[] min,
			double[] max
	) {}

	public record CategoriesResponse(
			List<String> hostile,
			List<String> passive,
			Map<String, String> categories
	) {}
}
