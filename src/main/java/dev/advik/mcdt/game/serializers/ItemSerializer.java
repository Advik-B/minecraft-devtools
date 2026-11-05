package dev.advik.mcdt.game.serializers;

import dev.advik.mcdt.game.McRegistries;
import dev.advik.mcdt.api.v1.dto.InventoryDto.SlotInfo;
import net.minecraft.world.item.ItemStack;

//? if >=1.21 {
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
//?}

import java.util.LinkedHashMap;
import java.util.Map;

public final class ItemSerializer {
	private ItemSerializer() {}

	public static SlotInfo toSlotInfo(int slot, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return SlotInfo.empty(slot);
		}

		String id = McRegistries.ITEM.getKey(stack.getItem()).toString();
		int count = stack.getCount();
		String name = stack.getHoverName().getString();

		Integer damage = stack.isDamageableItem() ? stack.getDamageValue() : null;
		Integer maxDamage = stack.isDamageableItem() ? stack.getMaxDamage() : null;
		String rarity = stack.getRarity().name().toLowerCase();

		// Data components arrived in 1.20.5; older items have NBT, which the `item-components` capability leaves out.
		Map<String, Object> components = new LinkedHashMap<>();
		//? if >=1.21 {
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
		//?}

		return new SlotInfo(
				slot,
				false,
				id,
				count,
				name,
				damage,
				maxDamage,
				rarity,
				components.isEmpty() ? null : components
		);
	}
}
