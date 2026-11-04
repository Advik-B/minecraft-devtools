package dev.advik.mcdt.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

//? if >=1.21.11 {
/*import net.minecraft.client.input.MouseButtonInfo;
*///?}

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	//? if >=1.21.11 {
	/*@Invoker("onButton")
	void invokeOnButton(long window, MouseButtonInfo button, int action);
	*///?} else {
	@Invoker("onPress")
	void invokeOnPress(long window, int button, int action, int modifiers);
	//?}

	@Invoker("onMove")
	void invokeOnMove(long window, double xpos, double ypos);

	@Invoker("onScroll")
	void invokeOnScroll(long window, double xoffset, double yoffset);
}
