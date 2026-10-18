package dev.advik.mcdt.mixin;

import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.http.WorldStreamWsHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void mcdt$onSendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		String blockId = BuiltInRegistries.BLOCK.getKey(newState.getBlock()).toString();
		EventHub.getInstance().publish("block.update", Map.of(
				"x", pos.getX(),
				"y", pos.getY(),
				"z", pos.getZ(),
				"block", blockId
		));
		WorldStreamWsHandler.broadcastBlockDiff(pos.getX(), pos.getY(), pos.getZ(), blockId, flags);
	}
}
