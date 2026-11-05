package dev.advik.mcdt.client;

import dev.advik.mcdt.game.McRegistries;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.McdtCore;
import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.baritone.BaritoneJobManager;
import dev.advik.mcdt.baritone.BaritoneService;
import dev.advik.mcdt.baritone.UnavailableBaritoneService;
import dev.advik.mcdt.config.ServerAllowlist;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.game.MinecraftGameClient;
import dev.advik.mcdt.game.input.MovementTracker;
import dev.advik.mcdt.reflection.HandleTable;
import dev.advik.mcdt.world.stream.WorldStreamService;
import dev.advik.mcdt.game.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Loader-neutral client lifecycle. Each loader's entrypoint registers its events and forwards them here.
 */
public final class McdtClient {
	private static volatile McdtCore core;

	private McdtClient() {
	}

	/**
	 * Starts the core once the client is up.
	 *
	 * @param mcdtVersion     this mod's version, from the loader's metadata
	 * @param baritoneVersion the installed Baritone mod's version, or null when it is not loaded
	 */
	public static void onClientStarted(Minecraft client, String mcdtVersion, String baritoneVersion) {
		try {
			Path gameDir = client.gameDirectory.toPath().toAbsolutePath();
			Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath();

			// The game's own version id (e.g. "1.21.4"). getLaunchedVersion() is the launcher profile name, which for
			// Fabric is something like "fabric-loader-0.19.5-1.21.4" and is not in Mojang's version manifest.
			String gameVersion = McCompat.gameVersionName();
			String playerName = client.getUser() != null ? client.getUser().getName() : "Player";

			boolean baritoneApiAvailable = false;
			if (baritoneVersion != null) {
				try {
					Class.forName("baritone.api.BaritoneAPI");
					baritoneApiAvailable = true;
				} catch (ClassNotFoundException | LinkageError e) {
					Mcdt.LOGGER.warn("Baritone is installed but BaritoneAPI is missing. You need the baritone-api jar, not the standalone one.");
				}
			}
			BaritoneService baritoneService = baritoneApiAvailable
					? new dev.advik.mcdt.baritone.bridge.BaritoneBridge(HandleTable.getInstance(), new BaritoneJobManager())
					: new UnavailableBaritoneService();

			McdtEnvironment env = new McdtEnvironment(
					mcdtVersion,
					gameVersion,
					gameDir,
					configDir(client),
					gameDir.resolve("logs").resolve(Mcdt.MOD_ID),
					userHome,
					playerName,
					baritoneApiAvailable ? baritoneVersion : "not-installed",
					Instant.now()
			);

			EventHub.setTickSource(() -> {
				McdtCore current = core;
				return current != null ? current.queue().clock().current() : 0L;
			});
			core = McdtCore.start(env, System.getenv(), MinecraftGameClient.getInstance(), baritoneService);
			Mcdt.LOGGER.info("MCDT initialized. API available at {}", core.server().baseUrl());
		} catch (IOException e) {
			Mcdt.LOGGER.error("Failed to start MCDT core", e);
		}
	}

	/** MCDT's config directory, {@code config/mcdt} in the game directory. */
	public static Path configDir(Minecraft client) {
		return client.gameDirectory.toPath().toAbsolutePath().resolve("config").resolve(Mcdt.MOD_ID);
	}

	public static void onEndClientTick(Minecraft client) {
		MovementTracker.getInstance().tick();
		McdtCore instance = core;
		if (instance != null) {
			instance.queue().clock().advance();
			long currentTick = instance.queue().clock().current();
			EventHub.getInstance().publish("tick", currentTick, Map.of("tick", currentTick));
			long budgetNanos = TimeUnit.MICROSECONDS.toNanos(instance.config().tickBudgetMicros);
			instance.queue().drain(budgetNanos);
			instance.server().worldStream().tick(budgetNanos);
		}
	}

	public static void onClientStopping(Minecraft client) {
		McdtCore instance = core;
		if (instance != null) {
			core = null;
			instance.close();
			Mcdt.LOGGER.info("MCDT shut down cleanly");
		}
	}

	public static void onJoin(Minecraft client) {
		String dimension = client.level != null ? McCompat.location(client.level.dimension()).toString() : "unknown";
		boolean isSingleplayer = McCompat.isSingleplayer(client);
		McdtCore instance = core;
		if (!isSingleplayer && instance != null) {
			ServerAllowlist.warnOnJoin(instance.config().allowedMultiplayerServers,
					client.getCurrentServer() != null ? client.getCurrentServer().ip : null);
		}
		EventHub.getInstance().publish("world.join", Map.of(
				"dimension", dimension,
				"singleplayer", isSingleplayer
		));
	}

	public static void onDisconnect() {
		HandleTable.getInstance().clear();
		EventHub.getInstance().publish("world.leave", Map.of(
				"reason", "disconnect"
		));
	}

	public static void onChatMessage(Component message) {
		EventHub.getInstance().publish("chat", Map.of(
				"text", message.getString(),
				"type", "chat"
		));
	}

	public static void onGameMessage(Component message, boolean overlay) {
		EventHub.getInstance().publish("chat", Map.of(
				"text", message.getString(),
				"overlay", overlay,
				"type", "game"
		));
	}

	public static void onChunkLoad(int chunkX, int chunkZ) {
		WorldStreamService.onChunkLoaded(chunkX, chunkZ);
	}

	public static void onChunkUnload(int chunkX, int chunkZ) {
		WorldStreamService.onChunkUnloaded(chunkX, chunkZ);
	}

	public static void onEntityLoad(Entity entity) {
		String type = McRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		EventHub.getInstance().publish("entity.spawn", Map.of(
				"id", entity.getId(),
				"uuid", entity.getUUID().toString(),
				"type", type,
				"x", entity.getX(),
				"y", entity.getY(),
				"z", entity.getZ()
		));
	}

	public static void onEntityUnload(Entity entity) {
		String reason = McCompat.removalReason(entity);
		EventHub.getInstance().publish("entity.remove", Map.of(
				"id", entity.getId(),
				"uuid", entity.getUUID().toString(),
				"reason", reason != null ? reason : "DISCARDED"
		));
	}

	public static Optional<McdtCore> core() {
		return Optional.ofNullable(core);
	}
}
