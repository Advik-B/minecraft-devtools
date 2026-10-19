package dev.advik.mcdt.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu entrypoint. Mod Menu only loads this class when it is installed. The config screen is offered only when
 * YACL is installed too; {@link McdtConfigScreen} is the only class that touches YACL types and is never loaded
 * otherwise, so neither mod is required.
 */
public final class McdtModMenuApi implements ModMenuApi {
	static final String YACL_MOD_ID = "yet_another_config_lib_v3";

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (FabricLoader.getInstance().isModLoaded(YACL_MOD_ID)) {
			return McdtConfigScreen::create;
		}
		return ModMenuApi.super.getModConfigScreenFactory();
	}
}
