package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.javalin.openapi.OpenApiName;

import java.util.List;
import java.util.Map;

/**
 * Request and response structures for the structured view /v1/view/blocks endpoints.
 */
public final class ViewBlocksDto {
	private ViewBlocksDto() {}

	@OpenApiName("ViewBlocksCameraInfo")
	public record CameraInfo(
			double[] pos,
			float yaw,
			float pitch,
			float fov
	) {}

	@OpenApiName("ViewBlocksBlockHit")
	public record BlockHit(
			int[] pos,
			int p,
			List<String> faces,
			double distance,
			double coverage
	) {}

	@OpenApiName("ViewBlocksEntityHit")
	public record EntityHit(
			int id,
			String uuid,
			String type,
			String name,
			double distance,
			double coverage
	) {}

	@OpenApiName("ViewBlocksResponse")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Response(
			long tick,
			CameraInfo camera,
			int[] res,
			List<String> palette,
			List<BlockHit> blocks,
			List<EntityHit> entities,
			double sky,
			List<Integer> labels,
			List<Double> depth
	) {}

	@OpenApiName("ViewBlocksNearestBlock")
	public record NearestBlock(
			int[] pos,
			double distance
	) {}

	@OpenApiName("ViewBlocksSummaryResponse")
	public record SummaryResponse(
			long tick,
			CameraInfo camera,
			Map<String, Integer> counts,
			Map<String, NearestBlock> nearest,
			RaycastDto crosshair
	) {}
}
