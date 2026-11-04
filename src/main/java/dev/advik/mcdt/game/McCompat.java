package dev.advik.mcdt.game;

import com.mojang.authlib.GameProfile;
import dev.advik.mcdt.mixin.KeyboardHandlerAccessor;
import dev.advik.mcdt.mixin.MouseHandlerAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

//? if >=26.1 {
/*import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.EntityHitResult;
*///?} else {
import net.minecraft.world.inventory.ClickType;
//?}

//? if >=1.21.11 {
/*import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;
*///?} else {
import dev.advik.mcdt.mixin.DebugRendererAccessor;
import dev.advik.mcdt.mixin.DebugScreenOverlayAccessor;
import net.minecraft.client.resources.PlayerSkin;
//?}

/**
 * Small Minecraft API differences between the supported nodes, so callers need no version conditionals.
 * A bound such as {@code >=1.21.11} names the first supported node with the change, which is not always the
 * Minecraft release that introduced it.
 */
public final class McCompat {
	private McCompat() {
	}

	/** The id of a resource key (a dimension, a registry entry). */
	public static ResourceLocation location(ResourceKey<?> key) {
		//? if >=1.21.11 {
		/*return key.identifier();
		*///?} else {
		return key.location();
		//?}
	}

	public static String gameVersionName() {
		//? if >=1.21.11 {
		/*return SharedConstants.getCurrentVersion().name();
		*///?} else {
		return SharedConstants.getCurrentVersion().getName();
		//?}
	}

	public static long windowHandle(Minecraft client) {
		//? if >=1.21.11 {
		/*return client.getWindow().handle();
		*///?} else {
		return client.getWindow().getWindow();
		//?}
	}

	// --- Inventory ---

	public static int selectedSlot(Inventory inventory) {
		//? if >=1.21.11 {
		/*return inventory.getSelectedSlot();
		*///?} else {
		return inventory.selected;
		//?}
	}

	public static void setSelectedSlot(Inventory inventory, int slot) {
		//? if >=1.21.11 {
		/*inventory.setSelectedSlot(slot);
		*///?} else {
		inventory.selected = slot;
		//?}
	}

	/** The 36 main slots (hotbar first). */
	public static List<ItemStack> mainItems(Inventory inventory) {
		//? if >=1.21.11 {
		/*return inventory.getNonEquipmentItems();
		*///?} else {
		return inventory.items;
		//?}
	}

	/** Armor, feet first (slots 36-39). */
	public static List<ItemStack> armor(Player player) {
		//? if >=1.21.11 {
		/*return List.of(
				player.getItemBySlot(EquipmentSlot.FEET),
				player.getItemBySlot(EquipmentSlot.LEGS),
				player.getItemBySlot(EquipmentSlot.CHEST),
				player.getItemBySlot(EquipmentSlot.HEAD));
		*///?} else {
		return player.getInventory().armor;
		//?}
	}

	/** The offhand slot (slot 40). */
	public static List<ItemStack> offhand(Player player) {
		//? if >=1.21.11 {
		/*return List.of(player.getItemBySlot(EquipmentSlot.OFFHAND));
		*///?} else {
		return player.getInventory().offhand;
		//?}
	}

	// --- World, players ---

	public static BlockPos spawnPos(ClientLevel level) {
		//? if >=1.21.11 {
		/*return level.getRespawnData().pos();
		*///?} else {
		return level.getSharedSpawnPos();
		//?}
	}

	public static String profileName(GameProfile profile) {
		//? if >=1.21.11 {
		/*return profile.name();
		*///?} else {
		return profile.getName();
		//?}
	}

	public static UUID profileId(GameProfile profile) {
		//? if >=1.21.11 {
		/*return profile.id();
		*///?} else {
		return profile.getId();
		//?}
	}

	/** A player's skin, in the shape the skin DTO needs. */
	public record Skin(ResourceLocation texture, String model, String textureUrl, String capeTexture, String elytraTexture,
			boolean secure) {
	}

	public static Skin skin(AbstractClientPlayer player) {
		return skin(player.getSkin());
	}

	public static Skin skin(PlayerInfo info) {
		return skin(info.getSkin());
	}

	private static Skin skin(PlayerSkin skin) {
		//? if >=1.21.11 {
		/*return new Skin(
				skin.body().texturePath(),
				skin.model() != null ? skin.model().getSerializedName() : "wide",
				skin.body() instanceof ClientAsset.DownloadedTexture downloaded ? downloaded.url() : null,
				skin.cape() != null ? skin.cape().texturePath().toString() : null,
				skin.elytra() != null ? skin.elytra().texturePath().toString() : null,
				skin.secure());
		*///?} else {
		return new Skin(
				skin.texture(),
				skin.model() != null ? skin.model().id() : "wide",
				skin.textureUrl(),
				skin.capeTexture() != null ? skin.capeTexture().toString() : null,
				skin.elytraTexture() != null ? skin.elytraTexture().toString() : null,
				skin.secure());
		//?}
	}

	// --- Options ---

	/** The graphics mode or preset, lower case ({@code fast}, {@code fancy}, {@code fabulous}, ...). */
	public static String graphicsMode(Options options) {
		//? if >=1.21.11 {
		/*return options.graphicsPreset().get().name().toLowerCase(Locale.ROOT);
		*///?} else {
		return options.graphicsMode().get().name().toLowerCase(Locale.ROOT);
		//?}
	}

	public static OptionInstance<Boolean> invertMouseY(Options options) {
		//? if >=1.21.11 {
		/*return options.invertMouseY();
		*///?} else {
		return options.invertYMouse();
		//?}
	}

	/** Leaves the world or server like the pause screen's disconnect button (a local world is saved first). */
	public static void disconnect(Minecraft client) {
		//? if >=1.21.11 {
		/*client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
		*///?} else {
		boolean local = client.isLocalServer();
		client.level.disconnect();
		if (local) {
			client.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(
					net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
		} else {
			client.disconnect();
		}
		//?}
	}

	// --- Debug screen ---

	/** The F3 screen's text: game information (left) and system information (right). */
	public record DebugLines(List<String> left, List<String> right) {
	}

	//? if >=26.1 {
	/*/^* F3 entries that the old screen showed on the right. ^/
	private static final Set<ResourceLocation> RIGHT_SIDE_ENTRIES = Set.of(
			DebugScreenEntries.MEMORY, DebugScreenEntries.SYSTEM_SPECS, DebugScreenEntries.GPU_UTILIZATION,
			DebugScreenEntries.LOOKING_AT_BLOCK_STATE, DebugScreenEntries.LOOKING_AT_BLOCK_TAGS,
			DebugScreenEntries.LOOKING_AT_FLUID_STATE, DebugScreenEntries.LOOKING_AT_FLUID_TAGS,
			DebugScreenEntries.LOOKING_AT_ENTITY, DebugScreenEntries.LOOKING_AT_ENTITY_TAGS);
	*///?} else if >=1.21.11 {
	/*/^* F3 entries that the old screen showed on the right. ^/
	private static final Set<ResourceLocation> RIGHT_SIDE_ENTRIES = Set.of(
			DebugScreenEntries.MEMORY, DebugScreenEntries.SYSTEM_SPECS, DebugScreenEntries.GPU_UTILIZATION,
			DebugScreenEntries.LOOKING_AT_BLOCK, DebugScreenEntries.LOOKING_AT_FLUID, DebugScreenEntries.LOOKING_AT_ENTITY);
	*///?}

	public static DebugLines debugLines(Minecraft client) {
		//? if >=1.21.11 {
		/*// The F3 screen is a set of entries; collect every entry the player may see, whether or not it's switched on.
		List<String> left = new ArrayList<>();
		List<String> right = new ArrayList<>();
		LevelChunk chunk = client.level != null && client.player != null ? client.level.getChunkAt(client.player.blockPosition()) : null;
		boolean reduced = client.showOnlyReducedInfo();
		for (Map.Entry<ResourceLocation, DebugScreenEntry> entry : DebugScreenEntries.allEntries().entrySet()) {
			if (!entry.getValue().isAllowed(reduced)) {
				continue;
			}
			List<String> target = RIGHT_SIDE_ENTRIES.contains(entry.getKey()) ? right : left;
			try {
				entry.getValue().display(new LineCollector(target), client.level, chunk, chunk);
			} catch (RuntimeException ignored) {
				// Entries assume a world, a server chunk or a target; skip the ones that can't run right now.
			}
		}
		return new DebugLines(left, right);
		*///?} else {
		List<String> left = List.of();
		List<String> right = List.of();
		if (client.gui != null && client.gui.getDebugOverlay() instanceof DebugScreenOverlayAccessor overlay) {
			try {
				if (client.level != null && client.getConnection() != null) {
					left = overlay.invokeGetGameInformation();
				}
				right = overlay.invokeGetSystemInformation();
			} catch (Exception ignored) {
			}
		}
		return new DebugLines(left, right);
		//?}
	}

	//? if >=1.21.11 {
	/*private record LineCollector(List<String> lines) implements DebugScreenDisplayer {
		@Override
		public void addPriorityLine(String line) {
			lines.add(line);
		}

		@Override
		public void addLine(String line) {
			lines.add(line);
		}

		@Override
		public void addToGroup(ResourceLocation group, Collection<String> groupLines) {
			lines.addAll(groupLines);
		}

		@Override
		public void addToGroup(ResourceLocation group, String line) {
			lines.add(line);
		}
	}
	*///?}

	public static boolean hitboxes(Minecraft client) {
		//? if >=1.21.11 {
		/*return client.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES);
		*///?} else {
		return client.getEntityRenderDispatcher().shouldRenderHitBoxes();
		//?}
	}

	public static void setHitboxes(Minecraft client, boolean on) {
		//? if >=1.21.11 {
		/*client.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES, on ? DebugScreenEntryStatus.ALWAYS_ON : DebugScreenEntryStatus.NEVER);
		*///?} else {
		client.getEntityRenderDispatcher().setRenderHitBoxes(on);
		//?}
	}

	public static boolean chunkBorders(Minecraft client) {
		//? if >=1.21.11 {
		/*return client.debugEntries.isCurrentlyEnabled(DebugScreenEntries.CHUNK_BORDERS);
		*///?} else {
		return client.debugRenderer instanceof DebugRendererAccessor renderer && renderer.getRenderChunkborder();
		//?}
	}

	public static void setChunkBorders(Minecraft client, boolean on) {
		//? if >=1.21.11 {
		/*client.debugEntries.setStatus(DebugScreenEntries.CHUNK_BORDERS, on ? DebugScreenEntryStatus.ALWAYS_ON : DebugScreenEntryStatus.NEVER);
		*///?} else {
		if (client.debugRenderer instanceof DebugRendererAccessor renderer) {
			renderer.setRenderChunkborder(on);
		}
		//?}
	}

	// --- Input (GLFW-level events, then screen and widget events) ---

	/** A raw key event, as GLFW would deliver it ({@code action}: 0 release, 1 press, 2 repeat). */
	public static void keyPress(Minecraft client, long window, int key, int scancode, int action) {
		//? if >=1.21.11 {
		/*((KeyboardHandlerAccessor) client.keyboardHandler).invokeKeyPress(window, action, new KeyEvent(key, scancode, 0));
		*///?} else {
		client.keyboardHandler.keyPress(window, key, scancode, action, 0);
		//?}
	}

	public static void charTyped(Minecraft client, long window, int codepoint) {
		//? if >=26.1 {
		/*((KeyboardHandlerAccessor) client.keyboardHandler).invokeCharTyped(window, new CharacterEvent(codepoint));
		*///?} else if >=1.21.11 {
		/*((KeyboardHandlerAccessor) client.keyboardHandler).invokeCharTyped(window, new CharacterEvent(codepoint, 0));
		*///?} else {
		((KeyboardHandlerAccessor) client.keyboardHandler).invokeCharTyped(window, codepoint, 0);
		//?}
	}

	/** A raw cursor move to window coordinates (since 26.1 the event also carries the delta). */
	public static void mouseMove(Minecraft client, long window, double x, double y) {
		//? if >=26.1 {
		/*((MouseHandlerAccessor) client.mouseHandler).invokeOnMove(window, x, y, x - client.mouseHandler.xpos(), y - client.mouseHandler.ypos());
		*///?} else {
		((MouseHandlerAccessor) client.mouseHandler).invokeOnMove(window, x, y);
		//?}
	}

	/** A raw mouse button event ({@code action}: 0 release, 1 press). */
	public static void mouseButton(Minecraft client, long window, int button, int action) {
		//? if >=1.21.11 {
		/*((MouseHandlerAccessor) client.mouseHandler).invokeOnButton(window, new MouseButtonInfo(button, 0), action);
		*///?} else {
		((MouseHandlerAccessor) client.mouseHandler).invokeOnPress(window, button, action, 0);
		//?}
	}

	public static void pressButton(Button button) {
		//? if >=1.21.11 {
		/*button.onPress(new KeyEvent(0, 0, 0));
		*///?} else {
		button.onPress();
		//?}
	}

	public static boolean mouseClicked(GuiEventListener target, double x, double y, int button) {
		//? if >=1.21.11 {
		/*return target.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), false);
		*///?} else {
		return target.mouseClicked(x, y, button);
		//?}
	}

	public static boolean charTyped(GuiEventListener target, char ch) {
		//? if >=26.1 {
		/*return target.charTyped(new CharacterEvent(ch));
		*///?} else if >=1.21.11 {
		/*return target.charTyped(new CharacterEvent(ch, 0));
		*///?} else {
		return target.charTyped(ch, 0);
		//?}
	}

	/** A key mapping's category: its translation key, or its id where categories are registered objects. */
	public static String keyCategory(KeyMapping mapping) {
		//? if >=1.21.11 {
		/*return mapping.getCategory().id().toString();
		*///?} else {
		return mapping.getCategory();
		//?}
	}

	// --- Screens, HUD, window ---

	public static Screen screen(Minecraft client) {
		//? if >=26.1 {
		/*return client.gui.screen();
		*///?} else {
		return client.screen;
		//?}
	}

	public static void setScreen(Minecraft client, Screen screen) {
		//? if >=26.1 {
		/*client.gui.setScreen(screen);
		*///?} else {
		client.setScreen(screen);
		//?}
	}

	/** What holds the HUD state (title, action bar, boss and subtitle overlays): the target of GuiAccessor. */
	public static Object hud(Minecraft client) {
		//? if >=26.1 {
		/*return client.gui.hud;
		*///?} else {
		return client.gui;
		//?}
	}

	public static ChatComponent chat(Minecraft client) {
		//? if >=26.1 {
		/*return client.gui.hud.getChat();
		*///?} else {
		return client.gui.getChat();
		//?}
	}

	public static BossHealthOverlay bossOverlay(Minecraft client) {
		//? if >=26.1 {
		/*return client.gui.hud.getBossOverlay();
		*///?} else {
		return client.gui.getBossOverlay();
		//?}
	}

	public static PlayerTabOverlay tabList(Minecraft client) {
		//? if >=26.1 {
		/*return client.gui.hud.getTabList();
		*///?} else {
		return client.gui.getTabList();
		//?}
	}

	/** The toast manager: the target of ToastManagerAccessor. */
	public static Object toastManager(Minecraft client) {
		//? if >=26.1 {
		/*return client.gui.toastManager();
		*///?} else if >=1.21.2 {
		return client.getToastManager();
		//?} else {
		/*return client.getToasts();
		*///?}
	}

	public static boolean isFullscreen(Minecraft client) {
		//? if >=26.1 {
		/*return client.options.fullscreen().get();
		*///?} else {
		return client.getWindow().isFullscreen();
		//?}
	}

	public static void setFullscreen(Minecraft client, boolean on) {
		if (on == isFullscreen(client)) {
			return;
		}
		//? if >=26.1 {
		/*client.getWindow().setFullscreen(on);
		*///?} else {
		client.getWindow().toggleFullScreen();
		//?}
		client.options.fullscreen().set(on);
	}

	/** A local world that isn't open to LAN. */
	public static boolean isSingleplayer(Minecraft client) {
		//? if >=26.1 {
		/*return client.hasSingleplayerServer() && !client.getSingleplayerServer().isPublished();
		*///?} else {
		return client.isSingleplayer();
		//?}
	}

	// --- World ---

	/** The time of day in ticks (the overworld clock since 26.1, which gave each dimension its own clock). */
	public static long dayTime(Level level) {
		//? if >=26.1 {
		/*return level.getOverworldClockTime();
		*///?} else {
		return level.getDayTime();
		//?}
	}

	public static boolean isPlayerType(EntityType<?> type) {
		//? if >=26.1 {
		/*return type == EntityTypes.PLAYER;
		*///?} else {
		return type == EntityType.PLAYER;
		//?}
	}

	public static boolean isItemType(EntityType<?> type) {
		//? if >=26.1 {
		/*return type == EntityTypes.ITEM;
		*///?} else {
		return type == EntityType.ITEM;
		//?}
	}

	public static int chunkX(ChunkPos pos) {
		//? if >=26.1 {
		/*return pos.x();
		*///?} else {
		return pos.x;
		//?}
	}

	public static int chunkZ(ChunkPos pos) {
		//? if >=26.1 {
		/*return pos.z();
		*///?} else {
		return pos.z;
		//?}
	}

	public static int uniqueFrameCount(SpriteContents contents) {
		//? if >=26.1 {
		/*return contents.getUniqueFrames().size();
		*///?} else {
		return (int) contents.getUniqueFrames().count();
		//?}
	}

	// --- Player actions ---

	public static void swing(LocalPlayer player, InteractionHand hand) {
		//? if >=26.1 {
		/*player.swing(hand, SwingAnimation.DEFAULT, false);
		*///?} else {
		player.swing(hand);
		//?}
	}

	/** Drops the selected item (or the whole stack), as the drop key does. */
	public static void dropSelected(Minecraft client, boolean all) {
		//? if >=26.1 {
		/*client.gameMode.dropItem(client.player, all);
		*///?} else {
		client.player.drop(all);
		//?}
	}

	public static void interact(Minecraft client, Entity target, InteractionHand hand) {
		//? if >=26.1 {
		/*client.gameMode.interact(client.player, target, new EntityHitResult(target), hand);
		*///?} else {
		client.gameMode.interact(client.player, target, hand);
		//?}
	}

	/**
	 * Clicks a container slot. {@code mode} is a click type name ({@code PICKUP}, {@code QUICK_MOVE}, ...); an unknown
	 * one throws IllegalArgumentException.
	 */
	public static void clickSlot(Minecraft client, int containerId, int slot, int button, String mode) {
		//? if >=26.1 {
		/*client.gameMode.handleContainerInput(containerId, slot, button, ContainerInput.valueOf(mode), client.player);
		*///?} else {
		client.gameMode.handleInventoryMouseClick(containerId, slot, button, ClickType.valueOf(mode), client.player);
		//?}
	}

	/** Opens the local world to LAN; {@code mode} is the game mode for players who join. */
	public static boolean publishToLan(IntegratedServer server, GameType mode, boolean commands, int port) {
		//? if >=26.1 {
		/*server.setDefaultGameType(mode);
		return server.publishServer(MinecraftServer.MultiplayerScope.LAN, commands, port);
		*///?} else {
		return server.publishServer(mode, commands, port);
		//?}
	}
}
