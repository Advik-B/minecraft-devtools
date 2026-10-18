package dev.advik.mcdt.game.serializers;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;

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
			return "{\"text\":\"" + component.getString().replace("\"", "\\\"") + "\"}";
		}
	}

	public static String toJson(Component component, HolderLookup.Provider provider) {
		if (component == null) {
			return null;
		}
		HolderLookup.Provider lookup = provider != null ? provider : RegistryAccess.EMPTY;
		try {
			return Component.Serializer.toJson(component, lookup);
		} catch (Exception e) {
			return "{\"text\":\"" + component.getString().replace("\"", "\\\"") + "\"}";
		}
	}
}
