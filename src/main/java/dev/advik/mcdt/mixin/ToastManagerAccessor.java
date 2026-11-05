package dev.advik.mcdt.mixin;

//? if >=1.21.2 {
import net.minecraft.client.gui.components.toasts.ToastManager;
//?} else {
/*import net.minecraft.client.gui.components.toasts.ToastComponent;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Visible toasts. 1.21.2 renamed {@code ToastComponent} to {@code ToastManager}; before 1.17 they're an array. */
//? if >=1.21.2 {
@Mixin(ToastManager.class)
//?} else {
/*@Mixin(ToastComponent.class)
*///?}
public interface ToastManagerAccessor {
	//? if >=1.21.2 {
	@Accessor("visibleToasts")
	List<?> getVisibleToasts();
	//?} else if >=1.21 {
	/*@Accessor("visible")
	List<?> getVisibleToasts();
	*///?} else {
	/*// ToastInstance is package-private; the 1.16.5 access widener (versions/1.16.5-fabric/resources) opens it.
	// Accessor interfaces can hold only accessors, so McCompat.visibleToasts turns this into a list.
	@Accessor("visible")
	ToastComponent.ToastInstance<?>[] getVisibleToastArray();
	*///?}
}
