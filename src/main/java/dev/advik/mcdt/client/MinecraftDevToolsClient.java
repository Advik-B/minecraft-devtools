package dev.advik.mcdt.client;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.McdtCore;
import dev.advik.mcdt.McdtEnvironment;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Client entrypoint wiring MCDT into Minecraft's client lifecycle and tick loop.
 */
public final class MinecraftDevToolsClient implements ClientModInitializer {
	private static volatile McdtCore core;

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(this::onClientStarted);
		ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(this::onClientStopping);
	}

	private void onClientStarted(Minecraft client) {
		try {
			Path gameDir = client.gameDirectory.toPath().toAbsolutePath();
			Path configDir = gameDir.resolve("config").resolve(Mcdt.MOD_ID);
			Path logDir = gameDir.resolve("logs").resolve(Mcdt.MOD_ID);
			Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath();

			String mcdtVersion = FabricLoader.getInstance()
					.getModContainer(Mcdt.MOD_ID)
					.map(ModContainer::getMetadata)
					.map(m -> m.getVersion().getFriendlyString())
					.orElse("0.1.0-SNAPSHOT");

			String gameVersion = client.getLaunchedVersion();
			if (gameVersion == null || gameVersion.isBlank()) {
				gameVersion = "1.21.4";
			}

			String playerName = client.getUser() != null ? client.getUser().getName() : "Player";

			String baritoneStatus = FabricLoader.getInstance().getModContainer("baritone")
					.map(ModContainer::getMetadata)
					.map(m -> m.getVersion().getFriendlyString())
					.orElse("not-installed");

			McdtEnvironment env = new McdtEnvironment(
					mcdtVersion,
					gameVersion,
					gameDir,
					configDir,
					logDir,
					userHome,
					playerName,
					baritoneStatus,
					Instant.now()
			);

			core = McdtCore.start(env);
			Mcdt.LOGGER.info("MCDT initialized. API available at {}", core.server().baseUrl());
		} catch (IOException e) {
			Mcdt.LOGGER.error("Failed to start MCDT core", e);
		}
	}

	private void onEndClientTick(Minecraft client) {
		dev.advik.mcdt.game.input.MovementTracker.getInstance().tick();
		McdtCore instance = core;
		if (instance != null) {
			instance.queue().clock().advance();
			long budgetNanos = TimeUnit.MICROSECONDS.toNanos(instance.config().tickBudgetMicros);
			instance.queue().drain(budgetNanos);
		}
	}

	private void onClientStopping(Minecraft client) {
		McdtCore instance = core;
		if (instance != null) {
			core = null;
			instance.close();
			Mcdt.LOGGER.info("MCDT shut down cleanly");
		}
	}

	public static Optional<McdtCore> core() {
		return Optional.ofNullable(core);
	}
}