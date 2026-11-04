//? if <1.21.11 {
package dev.advik.mcdt.mixin;

import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Applied only where chunk borders are a renderer flag (before 1.21.11; see McdtMixinPlugin). */
@Mixin(DebugRenderer.class)
public interface DebugRendererAccessor {
	@Accessor("renderChunkborder")
	boolean getRenderChunkborder();

	@Accessor("renderChunkborder")
	void setRenderChunkborder(boolean value);
}
//?}
