package dev.advik.mcdt.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
	//? if >=1.21 {
	@Invoker("startAttack")
	boolean invokeStartAttack();
	//?} else {
	/*@Invoker("startAttack")
	void invokeStartAttack();

	/^* The frame rate, which has no getter before 1.17. ^/
	@Accessor("fps")
	static int getFps() {
		throw new AssertionError();
	}
	*///?}

	@Invoker("startUseItem")
	void invokeStartUseItem();

	//? if >=26.1 {
	/*@Invoker("pickBlockOrEntity")
	*///?} else {
	@Invoker("pickBlock")
	//?}
	void invokePickBlock();
}
