package dev.advik.mcdt.spike;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;

/** MCP-named code shared by Forge and Legacy Fabric on 1.8.9 and 1.12.2. Called every client tick. */
public final class SpikeDriver {
	private static int ticks;
	private static boolean done;

	private SpikeDriver() {
	}

	public static void onTick() {
		SpikeReport.mixinApplied = true;
		if (done) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		ticks++;
		if (ticks < 40 || !(mc.currentScreen instanceof GuiMainMenu)) {
			return;
		}
		done = true;
		SpikeReport.finish();
		mc.shutdown();
	}
}
