package dev.advik.mcdt.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

//? if >=1.21.11 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
*///?}

@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {
	//? if >=1.21.11 {
	/*@Invoker("charTyped")
	void invokeCharTyped(long window, CharacterEvent event);

	@Invoker("keyPress")
	void invokeKeyPress(long window, int action, KeyEvent event);
	*///?} else {
	@Invoker("charTyped")
	void invokeCharTyped(long window, int codePoint, int modifiers);
	//?}
}
