package dev.advik.mcdt.spike.entry;

import dev.advik.mcdt.spike.SpikeReport;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class FabricEntry implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SpikeReport.loader = "legacyfabric";
		SpikeReport.minecraft = "1.12.2";
		SpikeReport.gameDir = FabricLoader.getInstance().getGameDir().toFile();
	}
}
