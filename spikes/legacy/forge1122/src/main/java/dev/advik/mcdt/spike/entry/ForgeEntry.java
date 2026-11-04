package dev.advik.mcdt.spike.entry;

import dev.advik.mcdt.spike.SpikeReport;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;

@Mod(modid = "mcdtspike", name = "MCDT spike", version = "0.0.0", acceptedMinecraftVersions = "[1.12.2]")
public class ForgeEntry {
	public ForgeEntry() {
		SpikeReport.loader = "forge";
		SpikeReport.minecraft = "1.12.2";
		SpikeReport.gameDir = Loader.instance().getConfigDir().getParentFile();
	}
}
