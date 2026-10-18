package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ScreenDto(
		boolean open,
		@JsonInclude(JsonInclude.Include.NON_NULL) String className,
		@JsonInclude(JsonInclude.Include.NON_NULL) String title,
		@JsonInclude(JsonInclude.Include.NON_NULL) Integer width,
		@JsonInclude(JsonInclude.Include.NON_NULL) Integer height,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<WidgetInfo> widgets,
		@JsonInclude(JsonInclude.Include.NON_NULL) ContainerInfo container
) {
	public static ScreenDto closed() {
		return new ScreenDto(false, null, null, null, null, null, null);
	}

	public record WidgetInfo(
			int index,
			String type,
			String message,
			WidgetBounds bounds,
			boolean active,
			boolean visible,
			boolean focused
	) {}

	public record WidgetBounds(int x, int y, int width, int height) {}

	public record ContainerInfo(
			String type,
			int slotCount,
			List<SlotDetail> slots
	) {}

	public record SlotDetail(
			int index,
			int x,
			int y,
			InventoryDto.SlotInfo item
	) {}
}
