package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Objects for the reflection layer endpoints.
 */
public final class ReflectionDto {
	private ReflectionDto() {
	}

	public record RootDescriptor(
			@JsonProperty("$handle") String handle,
			@JsonProperty("$class") String className
	) {
	}

	public record RootsResponse(
			Map<String, RootDescriptor> roots
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MethodInfo(
			String name,
			String returnType,
			List<String> parameterTypes,
			Boolean isStatic
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ObjectInspectResponse(
			@JsonProperty("$handle") String handle,
			@JsonProperty("$class") String className,
			Map<String, Object> fields,
			List<Map<String, Object>> methods,
			String path,
			Integer size,
			List<Object> items
	) {
	}

	public record FieldInspectResponse(
			String field,
			String declaringClass,
			String type,
			Object value
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ClassInspectResponse(
			@JsonProperty("$class") String className,
			String superClass,
			List<String> interfaces,
			Map<String, Object> staticFields,
			List<Map<String, Object>> staticMethods
	) {
	}

	public record HandleDeleteResponse(
			String handle,
			boolean released
	) {
	}
}
