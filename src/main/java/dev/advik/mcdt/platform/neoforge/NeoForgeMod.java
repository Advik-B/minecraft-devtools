//? if neoforge {
/*package dev.advik.mcdt.platform.neoforge;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.client.McdtClient;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/^*
 * NeoForge client entrypoint: registers NeoForge events and forwards them to {@link McdtClient}.
 ^/
@Mod(value = Mcdt.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeMod {
	/^* Baritone's NeoForge jar declares the mod id {@code baritoe}; {@code baritone} is checked too in case it is fixed. ^/
	private static final String[] BARITONE_MOD_IDS = {"baritoe", "baritone"};
	private static final String YACL_MOD_ID = "yet_another_config_lib_v3";

	private boolean started;

	public NeoForgeMod(IEventBus modBus, ModContainer container) {
		if (ModList.get().isLoaded(YACL_MOD_ID)) {
			NeoForgeConfigScreen.register(container);
		}

		// Started on the first client tick, once the game is fully up (Fabric's CLIENT_STARTED).
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
			Minecraft client = Minecraft.getInstance();
			if (!started) {
				started = true;
				McdtClient.onClientStarted(client, container.getModInfo().getVersion().toString(), baritoneVersion());
			}
			McdtClient.onEndClientTick(client);
		});
		NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> McdtClient.onClientStopping(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, event -> McdtClient.onJoin(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> McdtClient.onDisconnect());
		NeoForge.EVENT_BUS.addListener(ClientChatReceivedEvent.class, event -> {
			if (event instanceof ClientChatReceivedEvent.System system) {
				McdtClient.onGameMessage(system.getMessage(), system.isOverlay());
			} else {
				McdtClient.onChatMessage(event.getMessage());
			}
		});
		NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
			if (event.getLevel().isClientSide()) {
				McdtClient.onChunkLoad(event.getChunk().getPos().x, event.getChunk().getPos().z);
			}
		});
		NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
			if (event.getLevel().isClientSide()) {
				McdtClient.onChunkUnload(event.getChunk().getPos().x, event.getChunk().getPos().z);
			}
		});
		NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
			if (event.getLevel().isClientSide()) {
				McdtClient.onEntityLoad(event.getEntity());
			}
		});
		NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
			if (event.getLevel().isClientSide()) {
				McdtClient.onEntityUnload(event.getEntity());
			}
		});
	}

	private static String baritoneVersion() {
		for (String id : BARITONE_MOD_IDS) {
			var mod = ModList.get().getModContainerById(id);
			if (mod.isPresent()) {
				return mod.get().getModInfo().getVersion().toString();
			}
		}
		return null;
	}
}
*///?}
