package dev.advik.mcdt.api.v1.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BaritoneDto {
	private BaritoneDto() {
	}

	public record BlockPosDto(int x, int y, int z) {
	}

	public record StatusResponse(
			boolean installed,
			String activeProcess,
			GoalInfo goal,
			PathingInfo pathing
	) {
	}

	public record GoalInfo(
			String type,
			Map<String, Object> params
	) {
	}

	public record PathingInfo(
			boolean hasPath,
			boolean isPathing,
			boolean calculating,
			Double ticksRemaining
	) {
	}

	public record PathResponse(
			boolean hasPath,
			List<BlockPosDto> currentSegment,
			List<List<BlockPosDto>> nextSegments
	) {
	}

	public record GotoRequest(
			Integer x,
			Integer y,
			Integer z,
			Integer radius,
			String block
	) {
	}

	public record MineRequest(
			List<String> blocks,
			Integer quantity
	) {
		public MineRequest {
			if (blocks == null) {
				blocks = List.of();
			}
			if (quantity == null || quantity <= 0) {
				quantity = 1;
			}
		}
	}

	public record FollowRequest(
			String name,
			String uuid,
			String handle
	) {
	}

	public record ExploreRequest(
			Integer x,
			Integer z
	) {
	}

	public record FarmRequest(
			Integer range,
			BlockPosDto origin
	) {
	}

	public record BuildRequest(
			String name,
			BlockPosDto origin
	) {
	}

	public record CancelResponse(
			boolean cancelled
	) {
	}

	public record JobResponse(
			String jobId,
			String type,
			String status,
			String message,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record SettingInfo(
			String name,
			String type,
			Object defaultValue,
			Object value
	) {
	}

	public record CommandRequest(
			String command
	) {
	}

	public record CommandResponse(
			boolean success,
			String command
	) {
	}
}
