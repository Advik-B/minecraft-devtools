package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public record InventoryDto(
		int selectedSlot,
		List<SlotInfo> main,
		List<SlotInfo> armor,
		List<SlotInfo> offhand
) {
	public record SlotInfo(
			int slot,
			boolean empty,
			@JsonInclude(JsonInclude.Include.NON_NULL) String id,
			@JsonInclude(JsonInclude.Include.NON_DEFAULT) int count,
			@JsonInclude(JsonInclude.Include.NON_NULL) String name,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer damage,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer maxDamage,
			@JsonInclude(JsonInclude.Include.NON_NULL) String rarity,
			@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> components
	) {
		public SlotInfo(
				int slot,
				boolean empty,
				String id,
				int count,
				String name,
				Integer damage,
				Integer maxDamage,
				Map<String, Object> components
		) {
			this(slot, empty, id, count, name, damage, maxDamage, null, components);
		}

		public static SlotInfo empty(int slot) {
			return new SlotInfo(slot, true, null, 0, null, null, null, null, null);
		}
	}
}
