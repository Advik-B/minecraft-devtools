//? if <1.21 {
/*package dev.advik.mcdt.mixin;

import dev.advik.mcdt.client.McdtClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/^*
 * Chat events where Fabric API has no message events (before 1.19). Gui.handleChat gets every incoming message on
 * the client thread: chat, system messages and the action bar.
 ^/
@Mixin(Gui.class)
public abstract class GuiChatMixin {
	@Inject(method = "handleChat", at = @At("HEAD"))
	private void mcdt$onChat(ChatType type, Component message, UUID sender, CallbackInfo ci) {
		if (type == ChatType.CHAT) {
			McdtClient.onChatMessage(message);
		} else {
			McdtClient.onGameMessage(message, type == ChatType.GAME_INFO);
		}
	}
}
*///?}
