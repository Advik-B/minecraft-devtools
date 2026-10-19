package dev.advik.mcdt.network;

import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.reflection.HandleTable;
import dev.advik.mcdt.reflection.TypeConverter;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * Resolves serverbound packet classes and constructs instances from JSON payloads.
 */
public final class PacketResolver {
	private static final List<String> PACKET_PACKAGES = List.of(
			"net.minecraft.network.protocol.game.",
			"net.minecraft.network.protocol.common.",
			"net.minecraft.network.protocol.ping.",
			"net.minecraft.network.protocol.login.",
			"net.minecraft.network.protocol.configuration.",
			"net.minecraft.network.protocol.handshake."
	);

	private PacketResolver() {
	}

	public static Class<?> resolvePacketClass(String name) {
		if (name == null || name.isBlank()) {
			throw Problems.badRequest("Packet name cannot be null or empty");
		}

		String normalized = name.trim().replace('/', '.');
		// Handle inner class notation with dots e.g. ServerboundMovePlayerPacket.Pos
		int lastDot = normalized.lastIndexOf('.');
		if (lastDot > 0 && Character.isUpperCase(normalized.charAt(lastDot + 1))) {
			int prevDot = normalized.lastIndexOf('.', lastDot - 1);
			if (prevDot >= 0 && Character.isUpperCase(normalized.charAt(prevDot + 1))) {
				normalized = normalized.substring(0, lastDot) + "$" + normalized.substring(lastDot + 1);
			}
		}

		// Try direct load
		Class<?> clazz = tryLoadClass(normalized);
		if (clazz != null && Packet.class.isAssignableFrom(clazz)) {
			return clazz;
		}

		// Try prefixing packages
		for (String pkg : PACKET_PACKAGES) {
			clazz = tryLoadClass(pkg + normalized);
			if (clazz != null && Packet.class.isAssignableFrom(clazz)) {
				return clazz;
			}
		}

		// If name doesn't start with Serverbound, try Serverbound prefix
		if (!normalized.startsWith("Serverbound")) {
			String serverbound = "Serverbound" + normalized;
			for (String pkg : PACKET_PACKAGES) {
				clazz = tryLoadClass(pkg + serverbound);
				if (clazz != null && Packet.class.isAssignableFrom(clazz)) {
					return clazz;
				}
			}
		}

		throw Problems.classNotFound(name);
	}

	public static Object constructPacket(Class<?> packetClass, Map<String, Object> data, HandleTable handleTable) {
		if (packetClass == null) {
			throw Problems.badRequest("Packet class cannot be null");
		}

		Map<String, Object> normalizedData = new HashMap<>();
		if (data != null) {
			for (Map.Entry<String, Object> entry : data.entrySet()) {
				normalizedData.put(entry.getKey().toLowerCase(Locale.ROOT).replace("_", ""), entry.getValue());
			}
		}

		try {
			// Case 1: Record packet
			if (packetClass.isRecord()) {
				RecordComponent[] components = packetClass.getRecordComponents();
				Class<?>[] paramTypes = new Class<?>[components.length];
				Object[] args = new Object[components.length];

				for (int i = 0; i < components.length; i++) {
					RecordComponent rc = components[i];
					paramTypes[i] = rc.getType();
					String key = rc.getName().toLowerCase(Locale.ROOT).replace("_", "");
					Object rawVal = normalizedData.get(key);
					args[i] = TypeConverter.convert(rawVal, rc.getType(), handleTable);
				}

				Constructor<?> ctor = packetClass.getDeclaredConstructor(paramTypes);
				ctor.setAccessible(true);
				return ctor.newInstance(args);
			}

			// Case 2: Standard class with matching constructor
			Constructor<?>[] constructors = packetClass.getDeclaredConstructors();
			Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

			for (Constructor<?> ctor : constructors) {
				ctor.setAccessible(true);
				Class<?>[] paramTypes = ctor.getParameterTypes();

				// If 0-arg constructor and no data
				if (paramTypes.length == 0 && normalizedData.isEmpty()) {
					return ctor.newInstance();
				}

				// If parameter count matches data size or has all required fields
				if (paramTypes.length == normalizedData.size()) {
					try {
						Object[] args = new Object[paramTypes.length];
						int idx = 0;
						for (Object val : normalizedData.values()) {
							args[idx] = TypeConverter.convert(val, paramTypes[idx], handleTable);
							idx++;
						}
						return ctor.newInstance(args);
					} catch (Exception ignored) {
					}
				}
			}

			// Case 3: Instantiate 0-arg constructor and set fields
			for (Constructor<?> ctor : constructors) {
				if (ctor.getParameterCount() == 0) {
					ctor.setAccessible(true);
					Object instance = ctor.newInstance();
					for (Field f : packetClass.getDeclaredFields()) {
						String key = f.getName().toLowerCase(Locale.ROOT).replace("_", "");
						if (normalizedData.containsKey(key)) {
							f.setAccessible(true);
							Object val = TypeConverter.convert(normalizedData.get(key), f.getType(), handleTable);
							f.set(instance, val);
						}
					}
					return instance;
				}
			}

			throw Problems.packetFailed(packetClass.getSimpleName(), "Could not find compatible constructor for provided parameters");
		} catch (Exception e) {
			throw Problems.packetFailed(packetClass.getSimpleName(), e.getMessage());
		}
	}

	private static Class<?> tryLoadClass(String className) {
		try {
			return Class.forName(className);
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}
}
