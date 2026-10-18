package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record RaycastDto(
		String hitType,
		double[] pos,
		double distance,
		@JsonInclude(JsonInclude.Include.NON_NULL) BlockHit block,
		@JsonInclude(JsonInclude.Include.NON_NULL) EntityHit entity
) {
	public record BlockHit(
			int[] pos,
			String face,
			String state,
			boolean inside
	) {}

	public record EntityHit(
			int id,
			String uuid,
			String type,
			@JsonInclude(JsonInclude.Include.NON_NULL) String name
	) {}
}
