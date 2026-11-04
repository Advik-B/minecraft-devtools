package dev.advik.mcdt.game.serializers;

import net.minecraft.network.chat.Component;

//? if >=1.21 {
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.ComponentSerialization;
//?}

public final class TextSerializer {
	private TextSerializer() {}

	public static String toPlainText(Component component) {
		if (component == null) {
			return null;
		}
		return component.getString();
	}

	public static String toJson(Component component) {
		if (component == null) {
			return null;
		}
		//? if >=1.21 {
		try {
			Minecraft client = Minecraft.getInstance();
			HolderLookup.Provider provider = null;
			if (client != null) {
				if (client.level != null) {
					provider = client.level.registryAccess();
				} else if (client.getConnection() != null) {
					provider = client.getConnection().registryAccess();
				}
			}
			return toJson(component, provider);
		} catch (Throwable t) {
			return fallbackJson(component);
		}
		//?} else {
		/*// Before 1.20.5 components serialize without registry access.
		try {
			return Component.Serializer.toJson(component);
		} catch (RuntimeException e) {
			return fallbackJson(component);
		}
		*///?}
	}

	//? if >=1.21 {
	public static String toJson(Component component, HolderLookup.Provider provider) {
		if (component == null) {
			return null;
		}
		HolderLookup.Provider lookup = provider != null ? provider : RegistryAccess.EMPTY;
		try {
			return ComponentSerialization.CODEC.encodeStart(lookup.createSerializationContext(JsonOps.INSTANCE), component)
					.getOrThrow().toString();
		} catch (Exception e) {
			return fallbackJson(component);
		}
	}
	//?}

	private static String fallbackJson(Component component) {
		return "{\"text\":\"" + component.getString().replace("\"", "\\\"") + "\"}";
	}
}
