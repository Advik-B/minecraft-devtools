package dev.advik.mcdt.game;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.game.serializers.BlockStateSerializer;
import dev.advik.mcdt.game.serializers.EntitySerializer;
import dev.advik.mcdt.game.serializers.ItemSerializer;
import dev.advik.mcdt.game.serializers.TextSerializer;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.reflection.GameTypeAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;

/** Reflection-engine conversions for Minecraft 1.21.4 types. */
public final class MinecraftTypeAdapter implements GameTypeAdapter {
	@Override
	public Object serialize(Object obj) {
		if (obj instanceof Vec3 vec) {
			return Map.of("x", vec.x, "y", vec.y, "z", vec.z);
		}
		if (obj instanceof Vec3i pos) {
			return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
		}
		if (obj instanceof ResourceLocation res) {
			return res.toString();
		}
		if (obj instanceof Component comp) {
			String plain = TextSerializer.toPlainText(comp);
			String json = TextSerializer.toJson(comp);
			return Map.of("text", plain != null ? plain : "", "json", json != null ? json : "");
		}
		if (obj instanceof ItemStack stack) {
			return ItemSerializer.toSlotInfo(0, stack);
		}
		if (obj instanceof BlockState bs) {
			return BlockStateSerializer.toString(bs);
		}
		if (obj instanceof Entity ent) {
			return EntitySerializer.toEntityInfo(ent, null);
		}
		return UNHANDLED;
	}

	@Override
	public Object convert(JsonNode node, Class<?> targetType) {
		if (targetType == BlockPos.class || targetType == Vec3i.class) {
			if (node.isObject() && node.has("x") && node.has("y") && node.has("z")) {
				return new BlockPos(node.get("x").asInt(), node.get("y").asInt(), node.get("z").asInt());
			}
			if (node.isArray() && node.size() >= 3) {
				return new BlockPos(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
			}
			throw Problems.badRequest("Expected BlockPos as {x, y, z} or [x, y, z]");
		}

		if (targetType == Vec3.class) {
			if (node.isObject() && node.has("x") && node.has("y") && node.has("z")) {
				return new Vec3(node.get("x").asDouble(), node.get("y").asDouble(), node.get("z").asDouble());
			}
			if (node.isArray() && node.size() >= 3) {
				return new Vec3(node.get(0).asDouble(), node.get(1).asDouble(), node.get(2).asDouble());
			}
			throw Problems.badRequest("Expected Vec3 as {x, y, z} or [x, y, z]");
		}

		if (targetType == ResourceLocation.class) {
			return ResourceLocation.parse(node.asText());
		}

		if (targetType == InteractionHand.class) {
			String hand = node.asText();
			if ("off_hand".equalsIgnoreCase(hand) || "offhand".equalsIgnoreCase(hand)) {
				return InteractionHand.OFF_HAND;
			}
			return InteractionHand.MAIN_HAND;
		}

		if (targetType == Direction.class) {
			Direction dir = Direction.byName(node.asText().toLowerCase(Locale.ROOT));
			if (dir != null) {
				return dir;
			}
			throw Problems.badRequest("Unknown direction: " + node.asText());
		}
		return UNHANDLED;
	}
}
