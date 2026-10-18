package dev.advik.mcdt.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Invoker("onPress")
	void invokeOnPress(long window, int button, int action, int modifiers);

	@Invoker("onMove")
	void invokeOnMove(long window, double xpos, double ypos);

	@Invoker("onScroll")
	void invokeOnScroll(long window, double xoffset, double yoffset);
}
