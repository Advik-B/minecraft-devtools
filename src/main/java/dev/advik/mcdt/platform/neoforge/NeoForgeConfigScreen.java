//? if neoforge {
/*package dev.advik.mcdt.platform.neoforge;

import dev.advik.mcdt.client.config.McdtConfigScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/^*
 * Registers the YACL config screen with NeoForge's mod list. Only loaded when YACL is installed, because
 * {@link McdtConfigScreen} is the only class that touches YACL types.
 ^/
final class NeoForgeConfigScreen {
	private NeoForgeConfigScreen() {
	}

	static void register(ModContainer container) {
		container.registerExtensionPoint(IConfigScreenFactory.class,
				(IConfigScreenFactory) (mod, parent) -> McdtConfigScreen.create(parent));
	}
}
*///?}
