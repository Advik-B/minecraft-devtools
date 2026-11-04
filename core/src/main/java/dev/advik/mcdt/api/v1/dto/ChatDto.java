package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;
import java.util.List;

public final class ChatDto {
	private ChatDto() {}

	@OpenApiName("ChatSendRequest")
	public record SendRequest(
			String message,
			String command
	) {}

	@OpenApiName("ChatSendResponse")
	public record SendResponse(
			boolean sent,
			String type,
			String content,
			long tick
	) {}

	@OpenApiName("ChatMessageEntry")
	public record MessageEntry(
			String text,
			String json,
			int addedTime
	) {}

	@OpenApiName("ChatHistoryResponse")
	public record HistoryResponse(
			int total,
			int offset,
			int limit,
			List<MessageEntry> messages
	) {}
}
