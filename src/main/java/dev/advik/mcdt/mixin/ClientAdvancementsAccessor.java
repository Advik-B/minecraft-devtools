package dev.advik.mcdt.mixin;

import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

//? if >=1.21 {
import net.minecraft.advancements.AdvancementHolder;
//?} else {
/*import net.minecraft.advancements.Advancement;
*///?}

@Mixin(ClientAdvancements.class)
public interface ClientAdvancementsAccessor {
	//? if >=1.21 {
	@Accessor("progress")
	Map<AdvancementHolder, AdvancementProgress> getProgress();
	//?} else {
	/*@Accessor("progress")
	Map<Advancement, AdvancementProgress> getProgress();
	*///?}
}
