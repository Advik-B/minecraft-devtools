package dev.advik.mcdt.game.serializers;

import dev.advik.mcdt.api.v1.dto.InventoryDto.SlotInfo;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ItemSerializer {
	private ItemSerializer() {}

	public static SlotInfo toSlotInfo(int slot, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return SlotInfo.empty(slot);
		}

		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		int count = stack.getCount();
		String name = stack.getHoverName().getString();

		Integer damage = stack.isDamageableItem() ? stack.getDamageValue() : null;
		Integer maxDamage = stack.isDamageableItem() ? stack.getMaxDamage() : null;

		Map<String, Object> components = new LinkedHashMap<>();
		try {
			for (TypedDataComponent<?> typed : stack.getComponents()) {
				var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(typed.type());
				if (key != null) {
					components.put(key.toString(), String.valueOf(typed.value()));
				}
			}
		} catch (Throwable ignored) {
			// Fallback if component iteration encounters internal errors
		}

		return new SlotInfo(
				slot,
				false,
				id,
				count,
				name,
				damage,
				maxDamage,
				components.isEmpty() ? null : components
		);
	}
}
