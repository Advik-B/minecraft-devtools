package dev.advik.mcdt.api.v1.dto;

import java.util.List;

public final class ReflectionWriteDto {
	private ReflectionWriteDto() {
	}

	public record FieldPutRequest(
			Object value
	) {
	}

	public record FieldPutResponse(
			boolean success,
			String field,
			Object value
	) {
	}

	public record InvokeRequest(
			String method,
			List<Object> args
	) {
		public InvokeRequest {
			if (args == null) {
				args = List.of();
			}
		}
	}

	public record InvokeResponse(
			String method,
			String returnType,
			Object result
	) {
	}
}
