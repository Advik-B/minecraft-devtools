package dev.advik.mcdt.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class McpProtocolDto {
	public static final String PROTOCOL_VERSION = "2024-11-05";

	public record ServerInfo(
			String name,
			String version
	) {}

	public record ServerCapabilities(
			ToolsCapability tools,
			ResourcesCapability resources,
			LoggingCapability logging
	) {
		public static ServerCapabilities standard() {
			return new ServerCapabilities(
					new ToolsCapability(true),
					new ResourcesCapability(true, true),
					new LoggingCapability()
			);
		}
	}

	public record ToolsCapability(boolean listChanged) {}
	public record ResourcesCapability(boolean subscribe, boolean listChanged) {}
	public record LoggingCapability() {}

	public record InitializeResult(
			String protocolVersion,
			ServerCapabilities capabilities,
			ServerInfo serverInfo
	) {}

	public record ToolDefinition(
			String name,
			String description,
			JsonNode inputSchema
	) {}

	public record ToolListResult(
			List<ToolDefinition> tools
	) {}

	public record ContentBlock(
			String type,
			String text,
			String data,
			String mimeType
	) {
		public static ContentBlock text(String text) {
			return new ContentBlock("text", text, null, null);
		}

		public static ContentBlock image(String base64Data, String mimeType) {
			return new ContentBlock("image", null, base64Data, mimeType);
		}
	}

	public record ToolCallResult(
			List<ContentBlock> content,
			boolean isError
	) {
		public static ToolCallResult text(String text) {
			return new ToolCallResult(List.of(ContentBlock.text(text)), false);
		}

		public static ToolCallResult image(String base64Data, String mimeType) {
			return new ToolCallResult(List.of(ContentBlock.image(base64Data, mimeType)), false);
		}

		public static ToolCallResult error(String errorMessage) {
			return new ToolCallResult(List.of(ContentBlock.text(errorMessage)), true);
		}
	}

	public record ResourceDefinition(
			String uri,
			String name,
			String description,
			String mimeType
	) {}

	public record ResourceListResult(
			List<ResourceDefinition> resources
	) {}

	public record ResourceContent(
			String uri,
			String mimeType,
			String text
	) {}

	public record ResourceReadResult(
			List<ResourceContent> contents
	) {}

	public record ResourceUpdatedParams(
			String uri
	) {}

	private McpProtocolDto() {}
}
