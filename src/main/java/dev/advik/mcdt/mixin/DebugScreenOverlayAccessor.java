package dev.advik.mcdt.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public interface DebugScreenOverlayAccessor {
	@Invoker("getGameInformation")
	List<String> invokeGetGameInformation();

	@Invoker("getSystemInformation")
	List<String> invokeGetSystemInformation();
}
