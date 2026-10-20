package dev.advik.mcdt.mixin;

import net.minecraft.client.gui.components.SubtitleOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(SubtitleOverlay.class)
public interface SubtitleOverlayAccessor {
	@Accessor("subtitles")
	List<?> getSubtitles();
}
