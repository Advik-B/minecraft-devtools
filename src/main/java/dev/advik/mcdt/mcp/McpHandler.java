package dev.advik.mcdt.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.mcp.dto.JsonRpcMessage;
import dev.advik.mcdt.mcp.dto.McpProtocolDto;
import dev.advik.mcdt.mcp.dto.McpProtocolDto.*;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Handles HTTP POST and Server-Sent Events (SSE) GET requests for the Model Context Protocol (MCP) at /mcp.
 */
public final class McpHandler {
	private final McdtEnvironment env;
	private final McdtConfig config;
	private final TaskQueue queue;
	private final McpSessionManager sessionManager;
	private final McpToolRegistry toolRegistry;
	private final McpResourceRegistry resourceRegistry;
	private final McpToolLog toolLog;

	public McpHandler(
			McdtEnvironment env,
			McdtConfig config,
			TaskQueue queue,
			McpSessionManager sessionManager,
			McpToolRegistry toolRegistry,
			McpResourceRegistry resourceRegistry,
			McpToolLog toolLog
	) {
		this.env = env;
		this.config = config;
		this.queue = queue;
		this.sessionManager = sessionManager;
		this.toolRegistry = toolRegistry;
		this.resourceRegistry = resourceRegistry;
		this.toolLog = toolLog;
	}

	public void handlePost(Context ctx) {
		if (!config.enableMcp) {
			throw Problems.of(503, "service-unavailable", "Service Unavailable", "MCP server is disabled in MCDT configuration");
		}

		String sessionId = resolveSessionId(ctx);
		McpSession session = sessionManager.getOrCreate(sessionId);

		JsonNode bodyNode;
		try {
			bodyNode = Json.MAPPER.readTree(ctx.body());
		} catch (Exception e) {
			JsonRpcMessage.Response err = JsonRpcMessage.Response.error(null, JsonRpcMessage.PARSE_ERROR, "Parse error: " + e.getMessage());
			ctx.status(400).contentType("application/json").result(serialize(err));
			return;
		}

		if (bodyNode == null || bodyNode.isNull()) {
			JsonRpcMessage.Response err = JsonRpcMessage.Response.error(null, JsonRpcMessage.INVALID_REQUEST, "Empty request body");
			ctx.status(400).contentType("application/json").result(serialize(err));
			return;
		}

		if (bodyNode.isArray()) {
			// Batch request handling
			List<CompletableFuture<JsonRpcMessage.Response>> futures = new ArrayList<>();
			for (JsonNode item : bodyNode) {
				futures.add(processSingleRequest(item, session));
			}
			CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
					.thenAccept(v -> {
						List<JsonRpcMessage.Response> responses = futures.stream().map(CompletableFuture::join).toList();
						ctx.contentType("application/json").result(serialize(responses));
					});
			ctx.future(() -> all);
		} else {
			CompletableFuture<Void> future = processSingleRequest(bodyNode, session)
					.thenAccept(response -> {
						if (response != null) {
							ctx.contentType("application/json").result(serialize(response));
						} else {
							// Notification has no response
							ctx.status(204);
						}
					});
			ctx.future(() -> future);
		}
	}

	public void handleSse(SseClient client) {
		if (!config.enableMcp) {
			client.sendEvent("error", "MCP server is disabled");
			client.close();
			return;
		}

		String sessionId = resolveSessionId(client.ctx());
		McpSession session = sessionManager.getOrCreate(sessionId);
		session.attachSseClient(client);

		client.onClose(() -> session.detachSseClient());

		// Send endpoint advertisement event required by standard MCP SSE
		client.sendEvent("endpoint", "/mcp?sessionId=" + sessionId);
		client.keepAlive();
	}

	public void handleStatus(Context ctx) {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("enabled", config.enableMcp);
		status.put("version", env.mcdtVersion());
		status.put("protocolVersion", McpProtocolDto.PROTOCOL_VERSION);
		status.put("activeSessions", sessionManager.activeSessions().size());
		status.put("registeredTools", toolRegistry.listTools().size());
		status.put("permission", config.permission.id());
		ctx.contentType("application/json").result(serialize(status));
	}

	public void handleLog(Context ctx) {
		int limit = 50;
		String limitParam = ctx.queryParam("limit");
		if (limitParam != null) {
			try {
				limit = Integer.parseInt(limitParam);
			} catch (NumberFormatException ignored) {}
		}
		ctx.contentType("application/json").result(serialize(toolLog.getRecent(limit)));
	}

	private CompletableFuture<JsonRpcMessage.Response> processSingleRequest(JsonNode item, McpSession session) {
		JsonNode id = item.get("id");
		String method = item.has("method") && item.get("method").isTextual() ? item.get("method").asText() : null;
		JsonNode params = item.get("params");

		if (method == null) {
			return CompletableFuture.completedFuture(
					JsonRpcMessage.Response.error(id, JsonRpcMessage.INVALID_REQUEST, "Missing 'method' field")
			);
		}

		boolean isNotification = (id == null || id.isNull());

		return dispatchMethod(method, params, session, id).thenApply(res -> {
			if (isNotification) {
				return null;
			}
			return res;
		});
	}

	private CompletableFuture<JsonRpcMessage.Response> dispatchMethod(
			String method,
			JsonNode params,
			McpSession session,
			JsonNode id
	) {
		switch (method) {
			case "initialize" -> {
				InitializeResult result = new InitializeResult(
						McpProtocolDto.PROTOCOL_VERSION,
						ServerCapabilities.standard(),
						new ServerInfo("mcdt", env.mcdtVersion())
				);
				return CompletableFuture.completedFuture(JsonRpcMessage.Response.success(id, result));
			}
			case "notifications/initialized" -> {
				return CompletableFuture.completedFuture(JsonRpcMessage.Response.success(id, Map.of()));
			}
			case "ping" -> {
				return CompletableFuture.completedFuture(JsonRpcMessage.Response.success(id, Map.of()));
			}
			case "tools/list" -> {
				ToolListResult result = new ToolListResult(toolRegistry.listTools());
				return CompletableFuture.completedFuture(JsonRpcMessage.Response.success(id, result));
			}
			case "tools/call" -> {
				String toolName = params != null && params.has("name") ? params.get("name").asText() : "";
				JsonNode args = params != null && params.has("arguments") ? params.get("arguments") : null;
				long start = System.currentTimeMillis();

				return toolRegistry.callTool(toolName, args).thenApply(callResult -> {
					long duration = System.currentTimeMillis() - start;
					if (config.mcpLogCalls) {
						String argsSummary = args != null ? args.toString() : "{}";
						if (argsSummary.length() > 120) argsSummary = argsSummary.substring(0, 120) + "...";
						toolLog.record(
								session.sessionId(),
								toolName,
								argsSummary,
								duration,
								!callResult.isError(),
								callResult.isError() && !callResult.content().isEmpty() ? callResult.content().get(0).text() : null
						);
					}
					return JsonRpcMessage.Response.success(id, callResult);
				});
			}
			case "resources/list" -> {
				ResourceListResult result = resourceRegistry.listResources();
				return CompletableFuture.completedFuture(JsonRpcMessage.Response.success(id, result));
			}
			case "resources/read" -> {
				String uri = params != null && params.has("uri") ? params.get("uri").asText() : null;
				if (uri == null || uri.isBlank()) {
					return CompletableFuture.completedFuture(
							JsonRpcMessage.Response.error(id, JsonRpcMessage.INVALID_PARAMS, "Missing required parameter 'uri'")
					);
				}
				return resourceRegistry.readResource(uri, queue, config.requestTimeoutMs)
						.thenApply(readResult -> JsonRpcMessage.Response.success(id, readResult))
						.exceptionally(ex -> JsonRpcMessage.Response.error(id, JsonRpcMessage.INTERNAL_ERROR, ex.getMessage()));
			}
			case "resources/subscribe" -> {
				String uri = params != null && params.has("uri") ? params.get("uri").asText() : null;
				if (uri != null) {
					session.subscribe(uri);
				}
				return CompletableFuture.completedFuture(JsonRpcMessage.Response.success(id, Map.of()));
			}
			case "resources/unsubscribe" -> {
				String uri = params != null && params.has("uri") ? params.get("uri").asText() : null;
				if (uri != null) {
					session.unsubscribe(uri);
				}
				return CompletableFuture.completedFuture(JsonRpcMessage.Response.success(id, Map.of()));
			}
			default -> {
				return CompletableFuture.completedFuture(
						JsonRpcMessage.Response.error(id, JsonRpcMessage.METHOD_NOT_FOUND, "Method not found: " + method)
				);
			}
		}
	}

	private static String resolveSessionId(Context ctx) {
		String id = ctx.queryParam("sessionId");
		if (id != null && !id.isBlank()) return id.trim();
		id = ctx.header("Mcp-Session-Id");
		if (id != null && !id.isBlank()) return id.trim();
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static String serialize(Object obj) {
		try {
			return Json.MAPPER.writeValueAsString(obj);
		} catch (Exception e) {
			return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Serialization error\"}}";
		}
	}
}
