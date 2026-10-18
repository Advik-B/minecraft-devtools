package dev.advik.mcdt.mixin;

import dev.advik.mcdt.events.EventHub;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow @Nullable public Screen screen;

	@Inject(method = "setScreen", at = @At("HEAD"))
	private void mcdt$onSetScreen(@Nullable Screen newScreen, CallbackInfo ci) {
		Screen oldScreen = this.screen;
		if (oldScreen != null && (newScreen == null || oldScreen != newScreen)) {
			EventHub.getInstance().publish("screen.close", Map.of(
					"screen", oldScreen.getClass().getName()
			));
		}
		if (newScreen != null && (oldScreen == null || oldScreen != newScreen)) {
			EventHub.getInstance().publish("screen.open", Map.of(
					"screen", newScreen.getClass().getName(),
					"title", newScreen.getTitle().getString()
			));
		}
	}
}
