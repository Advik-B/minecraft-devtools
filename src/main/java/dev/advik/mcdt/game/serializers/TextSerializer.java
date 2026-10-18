package dev.advik.mcdt.game.serializers;

import net.minecraft.network.chat.Component;

public final class TextSerializer {
	private TextSerializer() {}

	public static String toPlainText(Component component) {
		if (component == null) {
			return null;
		}
		return component.getString();
	}
}
