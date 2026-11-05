//? if fabric {
package dev.advik.mcdt.platform.fabric;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.client.McdtClient;
import dev.advik.mcdt.game.McCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=1.21 {
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fabric client entrypoint: registers Fabric API events and forwards them to {@link McdtClient}.
 */
public final class FabricEntrypoint implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		//? if <1.21 {
		/*// Minecraft ships SLF4J only since 1.18: point the bundled copy at Log4j before anything logs. A service file would
		// do the same, but in dev runs SLF4J is on the system classpath and would load the provider too early.
		if (System.getProperty("slf4j.provider") == null) {
			System.setProperty("slf4j.provider", "dev.advik.mcdt.platform.fabric.Log4jSlf4jProvider");
		}
		*///?}
		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				McdtClient.onClientStarted(client, modVersion(Mcdt.MOD_ID), baritoneVersion()));
		ClientTickEvents.END_CLIENT_TICK.register(McdtClient::onEndClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(McdtClient::onClientStopping);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> McdtClient.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> McdtClient.onDisconnect());
		// Before 1.19 Fabric API has no message events; GuiChatMixin reports chat instead.
		//? if >=1.21 {
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
				McdtClient.onChatMessage(message));
		ClientReceiveMessageEvents.GAME.register(McdtClient::onGameMessage);
		//?}
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> McdtClient.onChunkLoad(McCompat.chunkX(chunk.getPos()), McCompat.chunkZ(chunk.getPos())));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> McdtClient.onChunkUnload(McCompat.chunkX(chunk.getPos()), McCompat.chunkZ(chunk.getPos())));
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> McdtClient.onEntityLoad(entity));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> McdtClient.onEntityUnload(entity));
	}

	private static String modVersion(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(ModContainer::getMetadata)
				.map(m -> m.getVersion().getFriendlyString())
				.orElse("0.1.0-SNAPSHOT");
	}

	private static String baritoneVersion() {
		return FabricLoader.getInstance().isModLoaded("baritone") ? modVersion("baritone") : null;
	}
}
//?}
