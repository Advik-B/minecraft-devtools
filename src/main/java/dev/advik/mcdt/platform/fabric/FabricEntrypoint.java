//? if fabric {
package dev.advik.mcdt.platform.fabric;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.client.McdtClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fabric client entrypoint: registers Fabric API events and forwards them to {@link McdtClient}.
 */
public final class FabricEntrypoint implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				McdtClient.onClientStarted(client, modVersion(Mcdt.MOD_ID), baritoneVersion()));
		ClientTickEvents.END_CLIENT_TICK.register(McdtClient::onEndClientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(McdtClient::onClientStopping);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> McdtClient.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> McdtClient.onDisconnect());
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
				McdtClient.onChatMessage(message));
		ClientReceiveMessageEvents.GAME.register(McdtClient::onGameMessage);
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> McdtClient.onChunkLoad(chunk.getPos().x, chunk.getPos().z));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> McdtClient.onChunkUnload(chunk.getPos().x, chunk.getPos().z));
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
