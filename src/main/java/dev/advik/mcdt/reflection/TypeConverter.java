package dev.advik.mcdt.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Converts JSON input values into target Java parameter types for field setting and method invocation.
 */
public final class TypeConverter {
	private TypeConverter() {
	}

	public static Object convert(Object input, Class<?> targetType, HandleTable handleTable) {
		if (targetType == null) {
			return input;
		}

		if (input == null) {
			if (targetType.isPrimitive()) {
				throw Problems.badRequest("Cannot assign null to primitive type " + targetType.getName());
			}
			return null;
		}

		JsonNode node;
		if (input instanceof JsonNode jn) {
			node = jn;
		} else {
			node = Json.MAPPER.valueToTree(input);
		}

		if (node.isNull()) {
			if (targetType.isPrimitive()) {
				throw Problems.badRequest("Cannot assign null to primitive type " + targetType.getName());
			}
			return null;
		}

		// Check if input is a handle descriptor: { "$handle": "h_123" } or string starting with "h_"
		String handleId = null;
		if (node.isObject() && node.has("$handle")) {
			handleId = node.get("$handle").asText();
		} else if (node.isTextual() && node.asText().startsWith("h_") && handleTable != null) {
			handleId = node.asText();
		}

		if (handleId != null && handleTable != null) {
			Object resolved = handleTable.get(handleId);
			if (resolved == null) {
				throw Problems.handleNotFound(handleId);
			}
			if (targetType.isInstance(resolved) || targetType == Object.class) {
				return resolved;
			}
			throw Problems.badRequest("Handle '" + handleId + "' holds instance of " + resolved.getClass().getName()
					+ " which cannot be assigned to " + targetType.getName());
		}

		// Primitives & boxed
		if (targetType == int.class || targetType == Integer.class) {
			return node.asInt();
		}
		if (targetType == long.class || targetType == Long.class) {
			return node.asLong();
		}
		if (targetType == double.class || targetType == Double.class) {
			return node.asDouble();
		}
		if (targetType == float.class || targetType == Float.class) {
			return (float) node.asDouble();
		}
		if (targetType == boolean.class || targetType == Boolean.class) {
			return node.asBoolean();
		}
		if (targetType == byte.class || targetType == Byte.class) {
			return (byte) node.asInt();
		}
		if (targetType == short.class || targetType == Short.class) {
			return (short) node.asInt();
		}
		if (targetType == char.class || targetType == Character.class) {
			String text = node.asText();
			return text.isEmpty() ? '\0' : text.charAt(0);
		}
		if (targetType == String.class) {
			return node.asText();
		}

		// Enums
		if (targetType.isEnum()) {
			String name = node.asText().trim();
			for (Object constant : targetType.getEnumConstants()) {
				Enum<?> e = (Enum<?>) constant;
				if (e.name().equalsIgnoreCase(name)) {
					return e;
				}
			}
			throw Problems.badRequest("Invalid enum value '" + name + "' for " + targetType.getSimpleName());
		}

		// Minecraft types
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

		if (targetType == Object.class) {
			if (node.isBoolean()) return node.asBoolean();
			if (node.isInt()) return node.asInt();
			if (node.isLong()) return node.asLong();
			if (node.isDouble() || node.isFloat()) return node.asDouble();
			if (node.isTextual()) return node.asText();
		}

		// Fallback to Jackson
		try {
			return Json.MAPPER.treeToValue(node, targetType);
		} catch (Exception e) {
			throw Problems.badRequest("Failed to convert input to " + targetType.getName() + ": " + e.getMessage());
		}
	}
}
