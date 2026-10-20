package dev.advik.mcdt.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.SubtitleOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Gui.class)
public interface GuiAccessor {
	@Accessor("overlayMessageString")
	Component getOverlayMessageString();

	@Accessor("overlayMessageTime")
	int getOverlayMessageTime();

	@Accessor("title")
	Component getTitle();

	@Accessor("subtitle")
	Component getSubtitle();

	@Accessor("titleFadeInTime")
	int getTitleFadeInTime();

	@Accessor("titleStayTime")
	int getTitleStayTime();

	@Accessor("titleFadeOutTime")
	int getTitleFadeOutTime();

	@Accessor("subtitleOverlay")
	SubtitleOverlay getSubtitleOverlay();

	@Accessor("bossOverlay")
	BossHealthOverlay getBossOverlayField();
}
