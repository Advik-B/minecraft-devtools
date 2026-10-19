package dev.advik.mcdt.mixin;

import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.http.PlayerStatusWsHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "hurtTo", at = @At("HEAD"))
	private void mcdt$onHurtTo(float health, CallbackInfo ci) {
		float oldHealth = ((LocalPlayer) (Object) this).getHealth();
		if (health < oldHealth) {
			float amount = oldHealth - health;
			EventHub.getInstance().publish("player.damage", Map.of(
					"amount", amount,
					"health", health
			));
			PlayerStatusWsHandler.broadcastDamage(amount, health, "generic", 0L);
			if (health <= 0.0f) {
				EventHub.getInstance().publish("player.death", Map.of(
						"message", "Player died"
				));
				PlayerStatusWsHandler.broadcastDeath("Player died", 0L);
			}
		}
	}
}
