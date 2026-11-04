package dev.advik.mcdt.game;

import dev.advik.mcdt.reflection.SessionClassProvider;
import net.minecraft.client.User;

/** Minecraft 1.21.4's session class, for reflection redaction. */
public final class MinecraftSessionClass implements SessionClassProvider {
	@Override
	public Class<?> sessionClass() {
		return User.class;
	}
}
