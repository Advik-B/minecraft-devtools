package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

import java.util.List;
import java.util.Map;

/**
 * Request and response structures for /v1/batch.
 */
public final class BatchDto {
	private BatchDto() {}

	@OpenApiName("BatchRequest")
	public record Request(
			List<SubRequest> requests
	) {
		@OpenApiName("BatchSubRequest")
		public record SubRequest(
				String id,
				String path
		) {}
	}

	@OpenApiName("BatchResponse")
	public record Response(
			long tick,
			Map<String, SubResponse> results
	) {
		@OpenApiName("BatchSubResponse")
		public record SubResponse(
				int status,
				Object data,
				Object problem
		) {}
	}
}
