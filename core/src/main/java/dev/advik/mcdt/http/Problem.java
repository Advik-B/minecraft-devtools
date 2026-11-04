package dev.advik.mcdt.http;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An RFC 9457 problem document. {@code type} is a stable machine-readable code such as {@code queue-full}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String type, String title, int status, String detail, String instance) {
	public static final String CONTENT_TYPE = "application/problem+json";

	public Problem withInstance(String instance) {
		return new Problem(type, title, status, detail, instance);
	}
}
