package dev.advik.mcdt.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.json.Json;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class JsonRpcMessage {
	public static final String VERSION = "2.0";

	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Request(
			String jsonrpc,
			JsonNode id,
			String method,
			JsonNode params
	) {
		public boolean isNotification() {
			return id == null || id.isNull();
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Response(
			String jsonrpc,
			JsonNode id,
			JsonNode result,
			Error error
	) {
		public static Response success(JsonNode id, Object result) {
			return new Response(VERSION, id, Json.MAPPER.valueToTree(result), null);
		}

		public static Response error(JsonNode id, int code, String message) {
			return new Response(VERSION, id, null, new Error(code, message, null));
		}

		public static Response error(JsonNode id, int code, String message, Object data) {
			return new Response(VERSION, id, null, new Error(code, message, data != null ? Json.MAPPER.valueToTree(data) : null));
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Error(
			int code,
			String message,
			JsonNode data
	) {}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Notification(
			String jsonrpc,
			String method,
			JsonNode params
	) {
		public static Notification of(String method, Object params) {
			return new Notification(VERSION, method, Json.MAPPER.valueToTree(params));
		}
	}

	private JsonRpcMessage() {}
}
