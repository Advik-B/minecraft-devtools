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
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			String dimension = client.level != null ? client.level.dimension().location().toString() : "unknown";
			boolean isSingleplayer = client.isSingleplayer();
			McdtCore instance = core;
			if (!isSingleplayer && instance != null) {
				dev.advik.mcdt.config.ServerAllowlist.warnOnJoin(instance.config().allowedMultiplayerServers,
						client.getCurrentServer() != null ? client.getCurrentServer().ip : null);
			}
			dev.advik.mcdt.events.EventHub.getInstance().publish("world.join", java.util.Map.of(
					"dimension", dimension,
					"singleplayer", isSingleplayer
			));
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			dev.advik.mcdt.reflection.HandleTable.getInstance().clear();
			dev.advik.mcdt.events.EventHub.getInstance().publish("world.leave", java.util.Map.of(
					"reason", "disconnect"
			));
		});
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			dev.advik.mcdt.events.EventHub.getInstance().publish("chat", java.util.Map.of(
					"text", message.getString(),
					"type", "chat"
			));
		});
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			dev.advik.mcdt.events.EventHub.getInstance().publish("chat", java.util.Map.of(
					"text", message.getString(),
					"overlay", overlay,
					"type", "game"
			));
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
			dev.advik.mcdt.events.EventHub.getInstance().publish("entity.spawn", java.util.Map.of(
					"id", entity.getId(),
					"uuid", entity.getUUID().toString(),
					"type", type,
					"x", entity.getX(),
					"y", entity.getY(),
					"z", entity.getZ()
			));
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			dev.advik.mcdt.events.EventHub.getInstance().publish("entity.remove", java.util.Map.of(
					"id", entity.getId(),
					"uuid", entity.getUUID().toString(),
					"reason", entity.getRemovalReason() != null ? entity.getRemovalReason().name() : "DISCARDED"
			));
		});
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

			boolean baritoneModLoaded = FabricLoader.getInstance().isModLoaded("baritone");
			boolean baritoneApiAvailable = false;
			dev.advik.mcdt.baritone.BaritoneService baritoneService;

			if (baritoneModLoaded) {
				try {
					Class.forName("baritone.api.BaritoneAPI");
					baritoneApiAvailable = true;
				} catch (ClassNotFoundException | LinkageError e) {
					Mcdt.LOGGER.warn("Baritone is installed but BaritoneAPI is missing. You need the baritone-api-fabric jar, not standalone-fabric.");
				}
			}

			if (baritoneApiAvailable) {
				baritoneService = new dev.advik.mcdt.baritone.bridge.BaritoneBridge(
						dev.advik.mcdt.reflection.HandleTable.getInstance(),
						new dev.advik.mcdt.baritone.BaritoneJobManager()
				);
			} else {
				baritoneService = new dev.advik.mcdt.baritone.UnavailableBaritoneService();
			}

			String baritoneStatus = baritoneApiAvailable
					? FabricLoader.getInstance().getModContainer("baritone")
							.map(ModContainer::getMetadata)
							.map(m -> m.getVersion().getFriendlyString())
							.orElse("1.13.1")
					: "not-installed";

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

			core = McdtCore.start(env, System.getenv(), dev.advik.mcdt.game.MinecraftGameClient.getInstance(), baritoneService);
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
			long currentTick = instance.queue().clock().current();
			dev.advik.mcdt.events.EventHub.getInstance().publish("tick", currentTick, java.util.Map.of("tick", currentTick));
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