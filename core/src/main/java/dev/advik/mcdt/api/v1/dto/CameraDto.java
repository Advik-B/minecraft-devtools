package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

/**
 * Request and response structures for /v1/camera.
 */
public final class CameraDto {
	private CameraDto() {}

	@OpenApiName("CameraRotation")
	public record Rotation(float yaw, float pitch) {}

	@OpenApiName("CameraResponse")
	public record Response(
			String perspective,
			double fov,
			double[] eyePosition,
			Rotation rotation
	) {}

	@OpenApiName("CameraRequest")
	public record Request(
			String perspective,
			Double fov
	) {}
}
