package dev.advik.mcdt.mixin;

//? if >=1.21.2 {
import net.minecraft.client.gui.components.toasts.ToastManager;
//?} else {
/*import net.minecraft.client.gui.components.toasts.ToastComponent;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Visible toasts. 1.21.2 renamed {@code ToastComponent} to {@code ToastManager}. */
//? if >=1.21.2 {
@Mixin(ToastManager.class)
//?} else {
/*@Mixin(ToastComponent.class)
*///?}
public interface ToastManagerAccessor {
	//? if >=1.21.2 {
	@Accessor("visibleToasts")
	//?} else {
	/*@Accessor("visible")
	*///?}
	List<?> getVisibleToasts();
}
