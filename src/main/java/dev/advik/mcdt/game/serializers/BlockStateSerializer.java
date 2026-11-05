package dev.advik.mcdt.game.serializers;

import dev.advik.mcdt.game.McRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.stream.Collectors;

public final class BlockStateSerializer {
	private BlockStateSerializer() {}

	public static String toString(BlockState state) {
		if (state == null) {
			return "minecraft:air";
		}

		String blockId = McRegistries.BLOCK.getKey(state.getBlock()).toString();
		Collection<Property<?>> properties = state.getProperties();
		if (properties.isEmpty()) {
			return blockId;
		}

		String props = properties.stream()
				.map(p -> p.getName() + "=" + valueName(state, p))
				.collect(Collectors.joining(","));

		return blockId + "[" + props + "]";
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
