package dev.advik.mcdt.mixin;

//? if >=26.1 {
/*import net.minecraft.client.multiplayer.chat.GuiMessage;
*///?} else {
import net.minecraft.client.GuiMessage;
//?}
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	//? if >=1.21 {
	@Accessor("allMessages")
	List<GuiMessage> getAllMessages();
	//?} else {
	/*@Accessor("allMessages")
	List<GuiMessage<Component>> getAllMessages();
	*///?}
}
