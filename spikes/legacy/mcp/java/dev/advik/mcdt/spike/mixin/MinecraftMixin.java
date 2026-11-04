package dev.advik.mcdt.spike.mixin;

import dev.advik.mcdt.spike.SpikeDriver;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void mcdtspike$tick(CallbackInfo ci) {
		SpikeDriver.onTick();
	}
}
