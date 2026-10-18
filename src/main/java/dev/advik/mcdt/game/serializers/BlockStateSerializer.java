package dev.advik.mcdt.game.serializers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.stream.Collectors;

public final class BlockStateSerializer {
	private BlockStateSerializer() {}

	public static String toString(BlockState state) {
		if (state == null) {
			return "minecraft:air";
		}

		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		Map<Property<?>, Comparable<?>> values = state.getValues();
		if (values.isEmpty()) {
			return blockId;
		}

		String props = values.entrySet().stream()
				.map(e -> e.getKey().getName() + "=" + getName(e.getKey(), e.getValue()))
				.collect(Collectors.joining(","));

		return blockId + "[" + props + "]";
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String getName(Property<T> property, Comparable<?> value) {
		return property.getName((T) value);
	}
}
