package dev.advik.mcdt.api.v1.dto;

import io.javalin.openapi.OpenApiName;

public final class ScreenSlotDto {
	private ScreenSlotDto() {}

	@OpenApiName("ScreenSlotRequest")
	public record Request(
			int slot,
			Integer button,
			String mode
	) {}

	@OpenApiName("ScreenSlotResponse")
	public record Response(
			int slot,
			String mode,
			InventoryDto.SlotInfo carriedItem,
			InventoryDto.SlotInfo slotItem,
			long tick
	) {}
}
