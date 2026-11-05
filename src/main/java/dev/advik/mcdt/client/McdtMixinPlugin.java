package dev.advik.mcdt.client;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Adds the mixins that exist only on some Minecraft versions; {@code mcdt.mixins.json} lists the ones every node
 * has. With {@code defaultRequire: 1}, listing a mixin whose target is gone would crash the game at startup.
 */
public final class McdtMixinPlugin implements IMixinConfigPlugin {
	@Override
	public List<String> getMixins() {
		if (MixinEnvironment.getCurrentEnvironment().getSide() != MixinEnvironment.Side.CLIENT) {
			return null;
		}
		//? if >=1.21.11 {
		/*return null;
		*///?} else if >=1.21 {
		return List.of("DebugRendererAccessor", "DebugScreenOverlayAccessor");
		//?} else {
		/*return List.of("DebugRendererAccessor", "DebugScreenOverlayAccessor", "GuiChatMixin");
		*///?}
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
