package dev.advik.mcdt.api.v1.dto;

import java.util.Map;

public final class PacketDto {
	private PacketDto() {
	}

	public record SendRequest(
			String packet,
			Map<String, Object> data
	) {
		public SendRequest {
			if (data == null) {
				data = Map.of();
			}
		}
	}

	public record SendResponse(
			boolean success,
			String packet,
			long tick
	) {
	}
}
