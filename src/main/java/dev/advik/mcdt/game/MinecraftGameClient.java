package dev.advik.mcdt.game;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.advik.mcdt.api.v1.dto.*;
import dev.advik.mcdt.game.input.MovementTracker;
import dev.advik.mcdt.game.raycast.RaycastUtil;
import dev.advik.mcdt.game.serializers.BlockStateSerializer;
import dev.advik.mcdt.game.serializers.EntitySerializer;
import dev.advik.mcdt.game.serializers.ItemSerializer;
import dev.advik.mcdt.game.serializers.TextSerializer;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.mixin.*;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.mojang.authlib.GameProfile;
import dev.advik.mcdt.mixin.StatsCounterAccessor;
import dev.advik.mcdt.mixin.ClientAdvancementsAccessor;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.GameType;
import net.minecraft.util.HttpUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.CameraType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class MinecraftGameClient implements GameClient {
	private static final MinecraftGameClient INSTANCE = new MinecraftGameClient();
	private final ConcurrentHashMap<String, byte[]> atlasImageCache = new ConcurrentHashMap<>();

	public static MinecraftGameClient getInstance() {
		return INSTANCE;
	}

	private MinecraftGameClient() {}

	@Override
	public ClientDto getClient() {
		Minecraft client = Minecraft.getInstance();
		int fps = client.getFps();

		Window window = client.getWindow();
		ClientDto.WindowInfo windowInfo = new ClientDto.WindowInfo(
				window.getWidth(),
				window.getHeight(),
				window.getGuiScaledWidth(),
				window.getGuiScaledHeight(),
				window.getGuiScale(),
				window.isFullscreen(),
				client.isWindowActive()
		);

		Screen screen = client.screen;
		ClientDto.ScreenSummary screenSummary = new ClientDto.ScreenSummary(
				screen != null,
				screen != null ? screen.getClass().getName() : null,
				screen != null ? TextSerializer.toPlainText(screen.getTitle()) : null
		);

		String connectionState;
		ClientDto.ServerInfo serverInfo = null;

		if (client.level == null) {
			connectionState = "menu";
		} else if (client.isLocalServer()) {
			connectionState = "singleplayer";
		} else {
			connectionState = "multiplayer";
			ServerData sd = client.getCurrentServer();
			if (sd != null) {
				serverInfo = new ClientDto.ServerInfo(
						sd.ip,
						sd.ping,
						sd.version != null ? TextSerializer.toPlainText(sd.version) : "",
						sd.motd != null ? TextSerializer.toPlainText(sd.motd) : "",
						sd.isLan()
				);
			}
		}

		return new ClientDto(fps, windowInfo, screenSummary, connectionState, serverInfo);
	}

	@Override
	public PlayerDto getPlayer() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			throw Problems.noPlayer();
		}

		double[] pos = new double[]{player.getX(), player.getY(), player.getZ()};
		PlayerDto.Rotation rotation = new PlayerDto.Rotation(player.getYRot(), player.getXRot());
		Vec3 vel = player.getDeltaMovement();
		double[] velocity = new double[]{vel.x, vel.y, vel.z};

		float health = player.getHealth();
		float maxHealth = (float) player.getAttributeValue(Attributes.MAX_HEALTH);
		float absorption = player.getAbsorptionAmount();

		int food = player.getFoodData().getFoodLevel();
		float saturation = player.getFoodData().getSaturationLevel();

		PlayerDto.Experience experience = new PlayerDto.Experience(
				player.experienceLevel,
				player.experienceProgress,
				player.totalExperience
		);

		String gameMode = client.gameMode != null && client.gameMode.getPlayerMode() != null
				? client.gameMode.getPlayerMode().getName()
				: "survival";

		String pose = player.getPose().name().toLowerCase();
		boolean onGround = player.onGround();
		boolean sneaking = player.isCrouching();
		boolean sprinting = player.isSprinting();
		boolean swimming = player.isSwimming();
		boolean sleeping = player.isSleeping();

		PlayerDto.AirSupply air = new PlayerDto.AirSupply(player.getAirSupply(), player.getMaxAirSupply());

		List<PlayerDto.EffectInfo> effects = new ArrayList<>();
		for (var effect : player.getActiveEffects()) {
			String id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString();
			effects.add(new PlayerDto.EffectInfo(
					id,
					effect.getAmplifier(),
					effect.getDuration(),
					effect.isAmbient(),
					effect.isVisible()
			));
		}

		var abilities = player.getAbilities();
		PlayerDto.Abilities ab = new PlayerDto.Abilities(
				abilities.flying,
				abilities.mayfly,
				abilities.instabuild,
				abilities.invulnerable
		);

		return new PlayerDto(
				pos, rotation, velocity, health, maxHealth, absorption,
				food, saturation, experience, gameMode, pose,
				onGround, sneaking, sprinting, swimming, sleeping,
				air, effects, ab
		);
	}

	@Override
	public InventoryDto getInventory() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			throw Problems.noPlayer();
		}

		int selectedSlot = player.getInventory().selected;
		NonNullList<ItemStack> items = player.getInventory().items;
		List<InventoryDto.SlotInfo> main = new ArrayList<>(items.size());
		for (int i = 0; i < items.size(); i++) {
			main.add(ItemSerializer.toSlotInfo(i, items.get(i)));
		}

		NonNullList<ItemStack> armor = player.getInventory().armor;
		List<InventoryDto.SlotInfo> armorList = new ArrayList<>(armor.size());
		for (int i = 0; i < armor.size(); i++) {
			armorList.add(ItemSerializer.toSlotInfo(36 + i, armor.get(i)));
		}

		NonNullList<ItemStack> offhand = player.getInventory().offhand;
		List<InventoryDto.SlotInfo> offhandList = new ArrayList<>(offhand.size());
		for (int i = 0; i < offhand.size(); i++) {
			offhandList.add(ItemSerializer.toSlotInfo(40 + i, offhand.get(i)));
		}

		return new InventoryDto(selectedSlot, main, armorList, offhandList);
	}

	@Override
	public WorldDto getWorld() {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null) {
			throw Problems.noWorld();
		}

		String dimension = level.dimension().location().toString();
		long dayTime = level.getDayTime();
		long gameTime = level.getGameTime();
		WorldDto.TimeInfo time = new WorldDto.TimeInfo(
				dayTime,
				gameTime,
				dayTime / 24000L,
				Math.floorMod(dayTime, 24000L)
		);

		WorldDto.WeatherInfo weather = new WorldDto.WeatherInfo(
				level.isRaining(),
				level.isThundering(),
				level.getRainLevel(1.0f),
				level.getThunderLevel(1.0f)
		);

		String difficulty = level.getDifficulty().getKey();
		boolean hardcore = level.getLevelData().isHardcore();
		BlockPos spawnPos = level.getSharedSpawnPos();
		WorldDto.SpawnInfo spawn = new WorldDto.SpawnInfo(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());

		int loadedChunks = level.getChunkSource() != null ? level.getChunkSource().getLoadedChunksCount() : 0;
		int entityCount = level.getEntityCount();

		return new WorldDto(dimension, time, weather, difficulty, hardcore, spawn, loadedChunks, entityCount);
	}

	@Override
	public BlocksDto getBlocks(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null) {
			throw Problems.noWorld();
		}

		int minX = Math.min(fromX, toX);
		int maxX = Math.max(fromX, toX);
		int minY = Math.min(fromY, toY);
		int maxY = Math.max(fromY, toY);
		int minZ = Math.min(fromZ, toZ);
		int maxZ = Math.max(fromZ, toZ);

		long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		if (volume > 32768) {
			throw Problems.blocksLimitExceeded(volume, 32768);
		}

		Map<String, Integer> paletteMap = new LinkedHashMap<>();
		List<BlocksDto.BlockEntry> blockEntries = new ArrayList<>((int) Math.min(volume, 4096));

		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					mpos.set(x, y, z);
					BlockState state = level.getBlockState(mpos);
					String stateStr = BlockStateSerializer.toString(state);
					int idx = paletteMap.computeIfAbsent(stateStr, k -> paletteMap.size());
					blockEntries.add(new BlocksDto.BlockEntry(new int[]{x, y, z}, idx));
				}
			}
		}

		return new BlocksDto(
				new BlocksDto.BlockBounds(new int[]{minX, minY, minZ}, new int[]{maxX, maxY, maxZ}, (int) volume),
				new ArrayList<>(paletteMap.keySet()),
				blockEntries
		);
	}

	@Override
	public EntitiesDto getEntities(Double radiusParam, String typeFilter, double[] centerParam, Integer limitParam) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null) {
			throw Problems.noWorld();
		}

		double radius = radiusParam != null ? radiusParam : 32.0;
		if (radius <= 0 || radius > 128.0) {
			throw Problems.invalidParameter("radius", "Radius must be between 0 and 128.");
		}

		int limit = limitParam != null ? limitParam : 100;
		if (limit <= 0 || limit > 500) {
			throw Problems.invalidParameter("limit", "Limit must be between 1 and 500.");
		}

		double[] center = centerParam;
		if (center == null) {
			if (client.player == null) {
				throw Problems.noPlayer();
			}
			center = new double[]{client.player.getX(), client.player.getY(), client.player.getZ()};
		}

		Vec3 centerVec = new Vec3(center[0], center[1], center[2]);
		AABB aabb = new AABB(
				center[0] - radius, center[1] - radius, center[2] - radius,
				center[0] + radius, center[1] + radius, center[2] + radius
		);

		List<Entity> list = level.getEntities((Entity) null, aabb, e -> {
			if (typeFilter != null && !typeFilter.isBlank()) {
				String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
				if (!typeId.equalsIgnoreCase(typeFilter) && !typeId.endsWith(":" + typeFilter)) {
					return false;
				}
			}
			return e.position().distanceTo(centerVec) <= radius;
		});

		list.sort(Comparator.comparingDouble(e -> e.position().distanceTo(centerVec)));

		List<EntitiesDto.EntityInfo> result = new ArrayList<>();
		for (int i = 0; i < Math.min(list.size(), limit); i++) {
			result.add(EntitySerializer.toEntityInfo(list.get(i), centerVec));
		}

		return new EntitiesDto(center, radius, result.size(), result);
	}

	@Override
	public EntitiesDto.CategoriesResponse getEntityCategories() {
		List<String> hostile = new ArrayList<>();
		List<String> passive = new ArrayList<>();
		Map<String, String> categories = new LinkedHashMap<>();

		for (var entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
			ResourceLocation rl = entry.getKey().location();
			net.minecraft.world.entity.EntityType<?> type = entry.getValue();
			String id = rl.toString();
			net.minecraft.world.entity.MobCategory mobCat = type.getCategory();

			String cat;
			if (type == net.minecraft.world.entity.EntityType.PLAYER) {
				cat = "player";
			} else if (type == net.minecraft.world.entity.EntityType.ITEM) {
				cat = "item";
			} else if (mobCat == net.minecraft.world.entity.MobCategory.MONSTER) {
				cat = "hostile";
				hostile.add(id);
			} else if (mobCat.isFriendly()) {
				cat = "passive";
				passive.add(id);
			} else {
				cat = "other";
			}
			categories.put(id, cat);
		}

		Collections.sort(hostile);
		Collections.sort(passive);
		return new EntitiesDto.CategoriesResponse(hostile, passive, categories);
	}

	@Override
	public RaycastDto raycast(double[] from, double[] direction, Double maxDistance, Boolean includeFluids, Boolean includeEntities) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null) {
			throw Problems.noWorld();
		}

		if (from == null && direction == null) {
			return RaycastUtil.fromHitResult(client.hitResult, level);
		}

		return RaycastUtil.performRaycast(level, client.player, from, direction, maxDistance, includeFluids, includeEntities);
	}

	@Override
	public ScreenDto getScreen() {
		Minecraft client = Minecraft.getInstance();
		Screen screen = client.screen;
		if (screen == null) {
			return ScreenDto.closed();
		}

		String className = screen.getClass().getName();
		String title = TextSerializer.toPlainText(screen.getTitle());
		int width = screen.width;
		int height = screen.height;

		List<ScreenDto.WidgetInfo> widgets = new ArrayList<>();
		int widgetIndex = 0;
		for (GuiEventListener listener : screen.children()) {
			if (listener instanceof AbstractWidget widget) {
				String type = "widget";
				if (widget instanceof Button) {
					type = "button";
				} else if (widget instanceof EditBox) {
					type = "edit_box";
				} else if (widget instanceof Checkbox) {
					type = "checkbox";
				}

				widgets.add(new ScreenDto.WidgetInfo(
						widgetIndex++,
						type,
						TextSerializer.toPlainText(widget.getMessage()),
						new ScreenDto.WidgetBounds(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
						widget.active,
						widget.visible,
						widget.isFocused()
				));
			}
		}

		ScreenDto.ContainerInfo containerInfo = null;
		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			var menu = containerScreen.getMenu();
			String menuType = BuiltInRegistries.MENU.getKey(menu.getType()).toString();
			List<ScreenDto.SlotDetail> slots = new ArrayList<>(menu.slots.size());
			for (Slot slot : menu.slots) {
				slots.add(new ScreenDto.SlotDetail(
						slot.index,
						slot.x,
						slot.y,
						ItemSerializer.toSlotInfo(slot.index, slot.getItem())
				));
			}
			containerInfo = new ScreenDto.ContainerInfo(menuType, slots.size(), slots);
		}

		return new ScreenDto(true, className, title, width, height, widgets, containerInfo);
	}

	@Override
	public RegistriesDto.ListResponse getRegistries() {
		List<String> list = BuiltInRegistries.REGISTRY.keySet().stream()
				.map(ResourceLocation::toString)
				.sorted()
				.toList();
		return new RegistriesDto.ListResponse(list.size(), list);
	}

	@Override
	public RegistriesDto.EntryResponse getRegistryEntries(String registryId, String query, int offset, int limit) {
		ResourceLocation rl = registryId.contains(":")
				? ResourceLocation.parse(registryId)
				: ResourceLocation.fromNamespaceAndPath("minecraft", registryId);

		Registry<?> registry = BuiltInRegistries.REGISTRY.getOptional(rl).orElse(null);
		if (registry == null) {
			throw Problems.registryNotFound(registryId);
		}

		List<String> allEntries = registry.keySet().stream()
				.map(ResourceLocation::toString)
				.sorted()
				.toList();

		if (query != null && !query.isBlank()) {
			String q = query.toLowerCase();
			allEntries = allEntries.stream()
					.filter(k -> k.toLowerCase().contains(q))
					.toList();
		}

		int total = allEntries.size();
		int start = Math.max(0, Math.min(offset, total));
		int end = Math.min(start + Math.max(1, limit), total);
		List<String> page = allEntries.subList(start, end);

		return new RegistriesDto.EntryResponse(rl.toString(), total, offset, page.size(), page);
	}

	@Override
	public OptionsDto getOptions() {
		Minecraft client = Minecraft.getInstance();
		var opt = client.options;

		int fov = opt.fov().get();
		int renderDistance = opt.renderDistance().get();
		int simulationDistance = opt.simulationDistance().get();
		int guiScale = opt.guiScale().get();
		double gamma = opt.gamma().get();
		int maxFps = opt.framerateLimit().get();
		boolean fullscreen = opt.fullscreen().get();
		boolean vsync = opt.enableVsync().get();
		String graphicsMode = opt.graphicsMode().get().name().toLowerCase();
		boolean bobView = opt.bobView().get();
		double mouseSensitivity = opt.sensitivity().get();
		boolean invertYMouse = opt.invertYMouse().get();

		Map<String, Float> volumes = new LinkedHashMap<>();
		for (SoundSource source : SoundSource.values()) {
			volumes.put(source.getName(), opt.getSoundSourceVolume(source));
		}

		List<OptionsDto.KeyMappingInfo> keyMappings = new ArrayList<>();
		for (KeyMapping km : opt.keyMappings) {
			keyMappings.add(new OptionsDto.KeyMappingInfo(
					km.getName(),
					km.getCategory(),
					km.getTranslatedKeyMessage().getString(),
					km.saveString()
			));
		}

		String language = opt.languageCode;

		return new OptionsDto(
				fov, renderDistance, simulationDistance, guiScale, gamma, maxFps,
				fullscreen, vsync, graphicsMode, bobView, mouseSensitivity, invertYMouse,
				volumes, keyMappings, language
		);
	}

	@Override
	public byte[] captureScreenshot(float scale) {
		Minecraft client = Minecraft.getInstance();
		NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
		int srcWidth = image.getWidth();
		int srcHeight = image.getHeight();
		int[] pixels = image.makePixelArray();
		image.close();

		float clampedScale = Math.max(0.1f, Math.min(1.0f, scale));
		int targetWidth = Math.max(1, Math.round(srcWidth * clampedScale));
		int targetHeight = Math.max(1, Math.round(srcHeight * clampedScale));

		BufferedImage bufferedImage = new BufferedImage(srcWidth, srcHeight, BufferedImage.TYPE_INT_ARGB);
		bufferedImage.setRGB(0, 0, srcWidth, srcHeight, pixels, 0, srcWidth);

		BufferedImage finalImage = bufferedImage;
		if (targetWidth != srcWidth || targetHeight != srcHeight) {
			BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2d = scaled.createGraphics();
			g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2d.drawImage(bufferedImage, 0, 0, targetWidth, targetHeight, null);
			g2d.dispose();
			finalImage = scaled;
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			ImageIO.write(finalImage, "png", baos);
		} catch (IOException e) {
			throw Problems.screenshotFailed("Failed to encode PNG screenshot: " + e.getMessage());
		}

		return baos.toByteArray();
	}

	@Override
	public PlayerSkinDto getPlayerSkinInfo(String targetPlayer, boolean embedImage) {
		GameProfile profile = resolvePlayerProfile(targetPlayer);
		PlayerSkin skin = resolvePlayerSkin(targetPlayer);

		String model = skin.model() != null ? skin.model().id() : "wide";
		String textureUrl = skin.textureUrl();
		String capeUrl = skin.capeTexture() != null ? skin.capeTexture().toString() : null;
		String elytraUrl = skin.elytraTexture() != null ? skin.elytraTexture().toString() : null;

		String base64 = null;
		if (embedImage) {
			byte[] bytes = getPlayerSkinImage(targetPlayer);
			base64 = Base64.getEncoder().encodeToString(bytes);
		}

		return new PlayerSkinDto(
				profile.getName(),
				profile.getId().toString(),
				model,
				textureUrl,
				capeUrl,
				elytraUrl,
				skin.secure(),
				base64
		);
	}

	@Override
	public byte[] getPlayerSkinImage(String targetPlayer) {
		PlayerSkin skin = resolvePlayerSkin(targetPlayer);
		return extractTextureBytes(skin.texture(), 64, 64);
	}

	private PlayerSkin resolvePlayerSkin(String targetPlayer) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		if (targetPlayer == null || targetPlayer.isBlank()
				|| targetPlayer.equalsIgnoreCase(client.player.getGameProfile().getName())
				|| targetPlayer.equalsIgnoreCase(client.player.getStringUUID())) {
			return client.player.getSkin();
		}

		if (client.getConnection() != null) {
			for (net.minecraft.client.multiplayer.PlayerInfo info : client.getConnection().getOnlinePlayers()) {
				if (info.getProfile().getName().equalsIgnoreCase(targetPlayer)
						|| info.getProfile().getId().toString().equalsIgnoreCase(targetPlayer)) {
					return info.getSkin();
				}
			}
		}

		throw Problems.playerNotFound(targetPlayer);
	}

	private GameProfile resolvePlayerProfile(String targetPlayer) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		if (targetPlayer == null || targetPlayer.isBlank()
				|| targetPlayer.equalsIgnoreCase(client.player.getGameProfile().getName())
				|| targetPlayer.equalsIgnoreCase(client.player.getStringUUID())) {
			return client.player.getGameProfile();
		}

		if (client.getConnection() != null) {
			for (net.minecraft.client.multiplayer.PlayerInfo info : client.getConnection().getOnlinePlayers()) {
				if (info.getProfile().getName().equalsIgnoreCase(targetPlayer)
						|| info.getProfile().getId().toString().equalsIgnoreCase(targetPlayer)) {
					return info.getProfile();
				}
			}
		}

		throw Problems.playerNotFound(targetPlayer);
	}

	@Override
	public TextureAtlasesDto getLoadedAtlases() {
		Minecraft client = Minecraft.getInstance();
		List<TextureAtlasesDto.AtlasSummary> summaries = new ArrayList<>();
		if (client.getTextureManager() instanceof TextureManagerAccessor tma) {
			for (Map.Entry<ResourceLocation, AbstractTexture> entry : tma.getByPath().entrySet()) {
				if (entry.getValue() instanceof TextureAtlas atlas) {
					int spriteCount = 0;
					int width = getAtlasWidth(atlas);
					int height = getAtlasHeight(atlas);
					if (atlas instanceof TextureAtlasAccessor taa && taa.getTexturesByName() != null) {
						spriteCount = taa.getTexturesByName().size();
					}
					summaries.add(new TextureAtlasesDto.AtlasSummary(
							entry.getKey().toString(),
							width,
							height,
							spriteCount
					));
				}
			}
		}

		if (summaries.isEmpty()) {
			AbstractTexture blocks = client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
			if (blocks instanceof TextureAtlas atlas) {
				int count = 0;
				if (atlas instanceof TextureAtlasAccessor taa && taa.getTexturesByName() != null) {
					count = taa.getTexturesByName().size();
				}
				summaries.add(new TextureAtlasesDto.AtlasSummary(
						TextureAtlas.LOCATION_BLOCKS.toString(),
						getAtlasWidth(atlas),
						getAtlasHeight(atlas),
						count
				));
			}
		}
		summaries.sort(Comparator.comparing(TextureAtlasesDto.AtlasSummary::id));
		return new TextureAtlasesDto(summaries.size(), summaries);
	}

	@Override
	public TextureAtlasDto getAtlasDetails(String atlasId, boolean embedImage) {
		TextureAtlas atlas = getTextureAtlas(atlasId);
		String idStr = atlas.location().toString();

		Map<String, TextureAtlasDto.SpriteInfo> sprites = new LinkedHashMap<>();
		if (atlas instanceof TextureAtlasAccessor taa && taa.getTexturesByName() != null) {
			for (Map.Entry<ResourceLocation, TextureAtlasSprite> entry : taa.getTexturesByName().entrySet()) {
				TextureAtlasSprite sprite = entry.getValue();
				var contents = sprite.contents();
				int frameCount = (int) contents.getUniqueFrames().count();
				boolean animated = frameCount > 1;

				sprites.put(entry.getKey().toString(), new TextureAtlasDto.SpriteInfo(
						sprite.getX(),
						sprite.getY(),
						contents.width(),
						contents.height(),
						sprite.getU0(),
						sprite.getU1(),
						sprite.getV0(),
						sprite.getV1(),
						animated,
						frameCount
				));
			}
		}

		String base64 = null;
		if (embedImage) {
			byte[] imageBytes = getAtlasImage(atlasId);
			base64 = Base64.getEncoder().encodeToString(imageBytes);
		}

		return new TextureAtlasDto(
				idStr,
				getAtlasWidth(atlas),
				getAtlasHeight(atlas),
				sprites.size(),
				sprites,
				base64
		);
	}

	@Override
	public byte[] getAtlasImage(String atlasId) {
		TextureAtlas atlas = getTextureAtlas(atlasId);
		return atlasImageCache.computeIfAbsent(atlas.location().toString(),
				k -> extractTextureBytes(atlas.location(), getAtlasWidth(atlas), getAtlasHeight(atlas)));
	}

	private int getAtlasWidth(TextureAtlas atlas) {
		return atlas instanceof TextureAtlasAccessor taa ? taa.getWidth() : 1024;
	}

	private int getAtlasHeight(TextureAtlas atlas) {
		return atlas instanceof TextureAtlasAccessor taa ? taa.getHeight() : 1024;
	}

	private ResourceLocation resolveAtlasLocation(String atlasId) {
		if (atlasId == null || atlasId.isBlank() || "blocks".equalsIgnoreCase(atlasId)) {
			return TextureAtlas.LOCATION_BLOCKS;
		}
		if ("particles".equalsIgnoreCase(atlasId)) {
			return TextureAtlas.LOCATION_PARTICLES;
		}
		if (atlasId.contains(":")) {
			return ResourceLocation.parse(atlasId);
		}
		return ResourceLocation.fromNamespaceAndPath("minecraft",
				atlasId.startsWith("textures/atlas/") ? atlasId : "textures/atlas/" + atlasId + ".png");
	}

	private TextureAtlas getTextureAtlas(String atlasId) {
		Minecraft client = Minecraft.getInstance();
		ResourceLocation loc = resolveAtlasLocation(atlasId);
		AbstractTexture texture = client.getTextureManager().getTexture(loc);
		if (texture instanceof TextureAtlas atlas) {
			return atlas;
		}
		if (client.getTextureManager() instanceof TextureManagerAccessor tma) {
			for (Map.Entry<ResourceLocation, AbstractTexture> entry : tma.getByPath().entrySet()) {
				if (entry.getValue() instanceof TextureAtlas atlas) {
					if (entry.getKey().equals(loc)
							|| entry.getKey().toString().equalsIgnoreCase(atlasId)
							|| entry.getKey().getPath().equalsIgnoreCase(atlasId)) {
						return atlas;
					}
				}
			}
		}
		throw Problems.atlasNotFound(atlasId != null ? atlasId : "blocks");
	}

	private byte[] extractTextureBytes(ResourceLocation loc, int defaultWidth, int defaultHeight) {
		Minecraft client = Minecraft.getInstance();
		var res = client.getResourceManager().getResource(loc);
		if (res.isPresent()) {
			try (var is = res.get().open()) {
				return is.readAllBytes();
			} catch (IOException ignored) {}
		}

		var texture = client.getTextureManager().getTexture(loc);
		if (texture != null && texture.getId() > 0) {
			int width = defaultWidth;
			int height = defaultHeight;
			if (texture instanceof TextureAtlas atlas) {
				width = getAtlasWidth(atlas);
				height = getAtlasHeight(atlas);
			}
			texture.bind();
			NativeImage nativeImage = new NativeImage(width, height, false);
			nativeImage.downloadTexture(0, false);
			int srcWidth = nativeImage.getWidth();
			int srcHeight = nativeImage.getHeight();
			int[] pixels = nativeImage.makePixelArray();
			nativeImage.close();

			BufferedImage bufferedImage = new BufferedImage(srcWidth, srcHeight, BufferedImage.TYPE_INT_ARGB);
			bufferedImage.setRGB(0, 0, srcWidth, srcHeight, pixels, 0, srcWidth);
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try {
				ImageIO.write(bufferedImage, "png", baos);
				return baos.toByteArray();
			} catch (IOException e) {
				throw Problems.textureFailed("Failed to encode texture as PNG: " + e.getMessage());
			}
		}

		throw Problems.textureFailed("Unable to retrieve texture data for " + loc);
	}

	// --- Milestone M2 Actions ---

	@Override
	public InputKeyDto.Response pressKey(InputKeyDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		long window = client.getWindow().getWindow();
		String action = request.action() != null ? request.action().toLowerCase(Locale.ROOT) : "tap";

		KeyMapping keyMapping = null;
		if (request.key() != null) {
			for (KeyMapping km : client.options.keyMappings) {
				if (km.getName().equalsIgnoreCase(request.key()) || km.saveString().equalsIgnoreCase(request.key())) {
					keyMapping = km;
					break;
				}
			}
		}

		int keyCode = request.keyCode() != null ? request.keyCode() : 0;
		int scanCode = request.scanCode() != null ? request.scanCode() : 0;

		if (keyMapping != null) {
			keyCode = keyMapping.getDefaultKey().getValue();
		} else if (keyCode == 0 && request.key() != null) {
			try {
				String keyName = request.key().startsWith("key.") ? request.key() : "key.keyboard." + request.key().toLowerCase(Locale.ROOT);
				InputConstants.Key k = InputConstants.getKey(keyName);
				keyCode = k.getValue();
			} catch (Exception ignored) {
			}
		}

		int glfwAction = switch (action) {
			case "press" -> 1;
			case "release" -> 0;
			case "repeat" -> 2;
			default -> 1;
		};

		if (keyMapping != null) {
			if ("press".equals(action)) {
				keyMapping.setDown(true);
			} else if ("release".equals(action)) {
				keyMapping.setDown(false);
			} else {
				keyMapping.setDown(true);
				KeyMapping.click(keyMapping.getDefaultKey());
			}
		}

		if (window != 0 && keyCode != 0) {
			client.keyboardHandler.keyPress(window, keyCode, scanCode, glfwAction, 0);
			if ("tap".equals(action)) {
				client.keyboardHandler.keyPress(window, keyCode, scanCode, 0, 0);
			}
		}

		if (request.text() != null && !request.text().isEmpty()) {
			var kbAccessor = (KeyboardHandlerAccessor) client.keyboardHandler;
			for (char ch : request.text().toCharArray()) {
				kbAccessor.invokeCharTyped(window, ch, 0);
			}
		}

		return new InputKeyDto.Response(true, action, request.key() != null ? request.key() : String.valueOf(keyCode), keyCode, -1);
	}

	@Override
	public InputMouseDto.Response mouseInput(InputMouseDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		long window = client.getWindow().getWindow();
		MouseHandlerAccessor accessor = (MouseHandlerAccessor) client.mouseHandler;
		String action = request.action() != null ? request.action().toLowerCase(Locale.ROOT) : "click";

		int buttonCode = 0;
		if (request.buttonCode() != null) {
			buttonCode = request.buttonCode();
		} else if (request.button() != null) {
			buttonCode = switch (request.button().toLowerCase(Locale.ROOT)) {
				case "right" -> 1;
				case "middle" -> 2;
				default -> 0;
			};
		}

		double curX = client.mouseHandler.xpos();
		double curY = client.mouseHandler.ypos();

		switch (action) {
			case "move" -> {
				double targetX = request.dx() != null ? curX + request.dx() : (request.x() != null ? request.x() : curX);
				double targetY = request.dy() != null ? curY + request.dy() : (request.y() != null ? request.y() : curY);
				accessor.invokeOnMove(window, targetX, targetY);
			}
			case "click" -> {
				if (request.x() != null && request.y() != null) {
					accessor.invokeOnMove(window, request.x(), request.y());
				}
				accessor.invokeOnPress(window, buttonCode, 1, 0);
				accessor.invokeOnPress(window, buttonCode, 0, 0);
			}
			case "press" -> {
				if (request.x() != null && request.y() != null) {
					accessor.invokeOnMove(window, request.x(), request.y());
				}
				accessor.invokeOnPress(window, buttonCode, 1, 0);
			}
			case "release" -> {
				if (request.x() != null && request.y() != null) {
					accessor.invokeOnMove(window, request.x(), request.y());
				}
				accessor.invokeOnPress(window, buttonCode, 0, 0);
			}
			case "scroll" -> {
				double sx = request.scrollX() != null ? request.scrollX() : 0.0;
				double sy = request.scrollY() != null ? request.scrollY() : 0.0;
				accessor.invokeOnScroll(window, sx, sy);
			}
			default -> throw Problems.invalidAction(action, "Unknown mouse action: " + action);
		}

		return new InputMouseDto.Response(true, action, client.mouseHandler.xpos(), client.mouseHandler.ypos(), -1);
	}

	@Override
	public ChatDto.SendResponse sendChat(ChatDto.SendRequest request) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}

		String text = request.message();
		boolean isCommand = false;
		if (request.command() != null && !request.command().isBlank()) {
			text = request.command();
			isCommand = true;
		}

		if (text == null || text.isBlank()) {
			throw Problems.invalidParameter("message", "Message or command text cannot be empty");
		}

		if (text.startsWith("/")) {
			isCommand = true;
			text = text.substring(1);
		}

		if (isCommand) {
			client.player.connection.sendCommand(text);
			return new ChatDto.SendResponse(true, "command", "/" + text, -1);
		} else {
			client.player.connection.sendChat(text);
			return new ChatDto.SendResponse(true, "chat", text, -1);
		}
	}

	@Override
	public ChatDto.HistoryResponse getChatHistory(int offset, int limit) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui == null || client.gui.getChat() == null) {
			return new ChatDto.HistoryResponse(0, offset, limit, List.of());
		}

		ChatComponentAccessor chatAccessor = (ChatComponentAccessor) client.gui.getChat();
		List<GuiMessage> allMessages = chatAccessor.getAllMessages();
		if (allMessages == null || allMessages.isEmpty()) {
			return new ChatDto.HistoryResponse(0, offset, limit, List.of());
		}

		List<ChatDto.MessageEntry> entries = new ArrayList<>(allMessages.size());
		for (GuiMessage msg : allMessages) {
			entries.add(new ChatDto.MessageEntry(
					TextSerializer.toPlainText(msg.content()),
					TextSerializer.toJson(msg.content()),
					msg.addedTime()
			));
		}

		int total = entries.size();
		int start = Math.max(0, Math.min(offset, total));
		int end = Math.min(start + Math.max(1, limit), total);
		List<ChatDto.MessageEntry> page = entries.subList(start, end);

		return new ChatDto.HistoryResponse(total, offset, page.size(), page);
	}

	@Override
	public PlayerLookDto.Response setPlayerLook(PlayerLookDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		LocalPlayer player = client.player;
		float yaw = player.getYRot();
		float pitch = player.getXRot();

		if (request.target() != null && request.target().length >= 3) {
			double dx = request.target()[0] - player.getX();
			double dy = request.target()[1] - player.getEyeY();
			double dz = request.target()[2] - player.getZ();
			double dXZ = Math.sqrt(dx * dx + dz * dz);
			yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			pitch = (float) Math.toDegrees(-Math.atan2(dy, dXZ));
		} else {
			if (request.yaw() != null) {
				yaw = request.yaw();
			} else if (request.deltaYaw() != null) {
				yaw += request.deltaYaw();
			}

			if (request.pitch() != null) {
				pitch = request.pitch();
			} else if (request.deltaPitch() != null) {
				pitch += request.deltaPitch();
			}
		}

		pitch = Math.max(-90.0f, Math.min(90.0f, pitch));
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.setYHeadRot(yaw);
		player.yRotO = yaw;
		player.xRotO = pitch;

		return new PlayerLookDto.Response(yaw, pitch, -1);
	}

	@Override
	public PlayerMoveDto.Response setPlayerMove(PlayerMoveDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		LocalPlayer player = client.player;
		if (Boolean.TRUE.equals(request.stop())) {
			MovementTracker.getInstance().stop();
			return new PlayerMoveDto.Response(false, false, false, false, false, false, false,
					player.getAbilities().flying, 0, -1);
		}

		if (request.fly() != null) {
			player.getAbilities().flying = request.fly();
			player.onUpdateAbilities();
		}
		if (Boolean.TRUE.equals(request.dismount())) {
			player.stopRiding();
		}

		boolean forward = Boolean.TRUE.equals(request.forward());
		boolean backward = Boolean.TRUE.equals(request.backward());
		boolean left = Boolean.TRUE.equals(request.left());
		boolean right = Boolean.TRUE.equals(request.right());
		boolean jump = Boolean.TRUE.equals(request.jump());
		boolean sneak = Boolean.TRUE.equals(request.sneak());
		boolean sprint = Boolean.TRUE.equals(request.sprint());
		int ticks = request.ticks() != null ? request.ticks() : 1;

		MovementTracker.getInstance().start(forward, backward, left, right, jump, sneak, sprint, ticks);

		return new PlayerMoveDto.Response(forward, backward, left, right, jump, sneak, sprint,
				player.getAbilities().flying, MovementTracker.getInstance().getRemainingTicks(), -1);
	}

	@Override
	public PlayerActionDto.Response performPlayerAction(PlayerActionDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		LocalPlayer player = client.player;
		String action = request.action() != null ? request.action().toLowerCase(Locale.ROOT) : "";
		MinecraftAccessor mcAccessor = (MinecraftAccessor) client;

		switch (action) {
			case "attack" -> {
				if (request.entityId() != null && client.level != null) {
					Entity target = client.level.getEntity(request.entityId());
					if (target != null && client.gameMode != null) {
						client.gameMode.attack(player, target);
						player.swing(InteractionHand.MAIN_HAND);
					} else {
						mcAccessor.invokeStartAttack();
					}
				} else {
					mcAccessor.invokeStartAttack();
				}
			}
			case "use" -> {
				if (request.pos() != null && request.pos().length >= 3 && client.gameMode != null) {
					BlockPos pos = new BlockPos(request.pos()[0], request.pos()[1], request.pos()[2]);
					Direction dir = parseDirection(request.face());
					BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), dir, pos, false);
					client.gameMode.useItemOn(player, parseHand(request.hand()), hit);
				} else {
					mcAccessor.invokeStartUseItem();
				}
			}
			case "stop_using" -> {
				if (client.gameMode != null) {
					client.gameMode.releaseUsingItem(player);
				}
			}
			case "swing" -> player.swing(parseHand(request.hand()));
			case "pick_block" -> mcAccessor.invokePickBlock();
			case "swap_hands" -> player.connection.send(new ServerboundPlayerActionPacket(
					ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
			case "select_hotbar" -> {
				if (request.slot() != null && request.slot() >= 0 && request.slot() <= 8) {
					player.getInventory().selected = request.slot();
				} else {
					throw Problems.invalidParameter("slot", "Hotbar slot index must be 0-8");
				}
			}
			case "drop" -> player.drop(Boolean.TRUE.equals(request.all()));
			case "respawn" -> player.respawn();
			case "interact_block" -> {
				if (request.pos() == null || request.pos().length < 3) {
					throw Problems.invalidParameter("pos", "Target block coordinates [x, y, z] required");
				}
				if (client.gameMode == null) {
					throw Problems.noWorld();
				}
				BlockPos pos = new BlockPos(request.pos()[0], request.pos()[1], request.pos()[2]);
				Direction dir = parseDirection(request.face());
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), dir, pos, false);
				client.gameMode.useItemOn(player, parseHand(request.hand()), hit);
			}
			case "interact_entity" -> {
				if (request.entityId() == null) {
					throw Problems.invalidParameter("entityId", "Target entity ID required");
				}
				if (client.level == null || client.gameMode == null) {
					throw Problems.noWorld();
				}
				Entity target = client.level.getEntity(request.entityId());
				if (target == null) {
					throw Problems.of(404, "entity-not-found", "Entity not found", "Entity with id " + request.entityId() + " not found");
				}
				InteractionHand hand = parseHand(request.hand());
				if ("attack".equalsIgnoreCase(request.interactionType())) {
					client.gameMode.attack(player, target);
				} else {
					client.gameMode.interact(player, target, hand);
				}
			}
			default -> throw Problems.invalidAction(action, "Unknown player action: " + action);
		}

		return new PlayerActionDto.Response(action, true, "Player action " + action + " executed", -1);
	}

	@Override
	public ScreenActionDto.Response performScreenAction(ScreenActionDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		Screen screen = client.screen;
		if (screen == null) {
			throw Problems.noScreen();
		}
		String action = request.action() != null ? request.action().toLowerCase(Locale.ROOT) : "click";
		if ("close".equals(action)) {
			screen.onClose();
			return new ScreenActionDto.Response(true, action, client.screen != null,
					client.screen != null ? client.screen.getClass().getName() : null,
					client.screen != null ? TextSerializer.toPlainText(client.screen.getTitle()) : null, -1);
		}

		if ("click".equals(action)) {
			if (request.widgetIndex() != null) {
				int idx = 0;
				AbstractWidget targetWidget = null;
				for (GuiEventListener listener : screen.children()) {
					if (listener instanceof AbstractWidget widget) {
						if (idx == request.widgetIndex()) {
							targetWidget = widget;
							break;
						}
						idx++;
					}
				}
				if (targetWidget == null) {
					throw Problems.widgetNotFound(request.widgetIndex());
				}
				int btn = request.button() != null ? request.button() : 0;
				if (targetWidget instanceof Button b) {
					b.onPress();
				} else {
					targetWidget.mouseClicked(targetWidget.getX() + targetWidget.getWidth() / 2.0,
							targetWidget.getY() + targetWidget.getHeight() / 2.0, btn);
				}
			} else if (request.x() != null && request.y() != null) {
				int btn = request.button() != null ? request.button() : 0;
				screen.mouseClicked(request.x(), request.y(), btn);
			} else {
				throw Problems.invalidParameter("widgetIndex/x,y", "Either widgetIndex or (x, y) coordinates must be provided");
			}
		} else if ("type".equals(action)) {
			if (request.text() == null) {
				throw Problems.invalidParameter("text", "Text to type must be provided");
			}
			if (request.widgetIndex() != null) {
				int idx = 0;
				AbstractWidget targetWidget = null;
				for (GuiEventListener listener : screen.children()) {
					if (listener instanceof AbstractWidget widget) {
						if (idx == request.widgetIndex()) {
							targetWidget = widget;
							break;
						}
						idx++;
					}
				}
				if (targetWidget instanceof EditBox editBox) {
					editBox.setValue(request.text());
				} else {
					for (char ch : request.text().toCharArray()) {
						screen.charTyped(ch, 0);
					}
				}
			} else {
				for (char ch : request.text().toCharArray()) {
					screen.charTyped(ch, 0);
				}
			}
		} else {
			throw Problems.invalidAction(action, "Unknown screen action: " + action);
		}

		return new ScreenActionDto.Response(true, action, client.screen != null,
				client.screen != null ? client.screen.getClass().getName() : null,
				client.screen != null ? TextSerializer.toPlainText(client.screen.getTitle()) : null, -1);
	}

	@Override
	public ScreenSlotDto.Response clickScreenSlot(ScreenSlotDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		Screen screen = client.screen;
		if (screen == null) {
			throw Problems.noScreen();
		}
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
			throw Problems.noContainer();
		}
		var menu = containerScreen.getMenu();
		int slot = request.slot();
		if (slot < 0 || slot >= menu.slots.size()) {
			throw Problems.slotOutOfBounds(slot, menu.slots.size());
		}

		String modeStr = request.mode() != null ? request.mode().toUpperCase(Locale.ROOT) : "PICKUP";
		ClickType clickType;
		try {
			clickType = ClickType.valueOf(modeStr);
		} catch (IllegalArgumentException e) {
			throw Problems.invalidParameter("mode", "Unknown click type mode: " + request.mode());
		}

		int button = request.button() != null ? request.button() : 0;
		if (client.gameMode != null && client.player != null) {
			client.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, clickType, client.player);
		}

		ItemStack carried = menu.getCarried();
		ItemStack clicked = menu.getSlot(slot).getItem();

		return new ScreenSlotDto.Response(slot, modeStr.toLowerCase(Locale.ROOT),
				ItemSerializer.toSlotInfo(-1, carried),
				ItemSerializer.toSlotInfo(slot, clicked), -1);
	}

	@Override
	public OptionsDto patchOptions(OptionsPatchDto request) {
		Minecraft client = Minecraft.getInstance();
		var opt = client.options;

		if (request.fov() != null) opt.fov().set(request.fov());
		if (request.renderDistance() != null) opt.renderDistance().set(request.renderDistance());
		if (request.simulationDistance() != null) opt.simulationDistance().set(request.simulationDistance());
		if (request.guiScale() != null) opt.guiScale().set(request.guiScale());
		if (request.gamma() != null) opt.gamma().set(request.gamma());
		if (request.maxFps() != null) opt.framerateLimit().set(request.maxFps());
		if (request.fullscreen() != null) opt.fullscreen().set(request.fullscreen());
		if (request.vsync() != null) opt.enableVsync().set(request.vsync());
		if (request.bobView() != null) opt.bobView().set(request.bobView());
		if (request.mouseSensitivity() != null) opt.sensitivity().set(request.mouseSensitivity());
		if (request.invertYMouse() != null) opt.invertYMouse().set(request.invertYMouse());
		if (request.pauseOnLostFocus() != null) opt.pauseOnLostFocus = request.pauseOnLostFocus();
		if (request.language() != null && !request.language().isBlank()) opt.languageCode = request.language();

		if (request.soundVolumes() != null) {
			for (var entry : request.soundVolumes().entrySet()) {
				for (SoundSource source : SoundSource.values()) {
					if (source.getName().equalsIgnoreCase(entry.getKey())) {
						opt.getSoundSourceOptionInstance(source).set(entry.getValue().doubleValue());
						break;
					}
				}
			}
		}

		opt.save();
		return getOptions();
	}

	@Override
	public void quitGame() {
		Minecraft client = Minecraft.getInstance();
		client.stop();
	}

	private static Direction parseDirection(String face) {
		if (face == null || face.isBlank()) {
			return Direction.UP;
		}
		Direction dir = Direction.byName(face.toLowerCase(Locale.ROOT));
		return dir != null ? dir : Direction.UP;
	}

	private static InteractionHand parseHand(String hand) {
		if (hand != null && ("off_hand".equalsIgnoreCase(hand) || "offhand".equalsIgnoreCase(hand))) {
			return InteractionHand.OFF_HAND;
		}
		return InteractionHand.MAIN_HAND;
	}

	@Override
	public Object getRootObject(String name) {
		if (name == null) {
			return null;
		}
		Minecraft client = Minecraft.getInstance();
		return switch (name) {
			case "minecraft" -> client;
			case "player" -> client.player;
			case "level" -> client.level;
			case "screen" -> client.screen;
			case "options" -> client.options;
			case "gameRenderer" -> client.gameRenderer;
			default -> null;
		};
	}

	@Override
	public Map<String, Object> getRootObjects() {
		Minecraft client = Minecraft.getInstance();
		Map<String, Object> roots = new LinkedHashMap<>();
		roots.put("minecraft", client);
		roots.put("player", client.player);
		roots.put("level", client.level);
		roots.put("screen", client.screen);
		roots.put("options", client.options);
		roots.put("gameRenderer", client.gameRenderer);
		return roots;
	}

	@Override
	public boolean isSingleplayer() {
		return Minecraft.getInstance().isSingleplayer();
	}

	@Override
	public boolean isMultiplayer() {
		Minecraft client = Minecraft.getInstance();
		return client.level != null && !client.isLocalServer();
	}

	@Override
	public String getCurrentServerAddress() {
		Minecraft client = Minecraft.getInstance();
		ServerData server = client.getCurrentServer();
		return server != null ? server.ip : null;
	}

	@Override
	public void sendPacket(Object packet) {
		if (packet instanceof Packet<?> p) {
			Minecraft client = Minecraft.getInstance();
			if (client.getConnection() == null) {
				throw Problems.noWorld();
			}
			client.getConnection().send(p);
		} else {
			throw Problems.badRequest("Object is not a Packet");
		}
	}

	@Override
	public ViewBlocksDto.Response getViewBlocks(
			String res,
			Double maxDistance,
			Boolean fluids,
			Boolean passThrough,
			Boolean entities,
			Boolean grid,
			Double minCoverage
	) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) throw Problems.noWorld();
		LocalPlayer player = client.player;
		if (player == null) throw Problems.noPlayer();

		int width = 128;
		int height = 72;
		if (res != null && !res.isBlank()) {
			String[] parts = res.trim().toLowerCase().split("x");
			if (parts.length == 2) {
				try {
					width = Integer.parseInt(parts[0]);
					height = Integer.parseInt(parts[1]);
				} catch (NumberFormatException e) {
					throw Problems.invalidParameter("res", "Expected format WxH (e.g. 128x72)");
				}
			} else {
				throw Problems.invalidParameter("res", "Expected format WxH (e.g. 128x72)");
			}
		}

		if (width <= 0 || height <= 0 || width > 512 || height > 288) {
			throw Problems.invalidParameter("res", "Resolution must be between 1x1 and 512x288");
		}

		double maxDist = maxDistance != null ? Math.min(128.0, Math.max(1.0, maxDistance)) : 64.0;
		boolean includeFluids = fluids == null || fluids;
		boolean allowPassThrough = passThrough == null || passThrough;
		double minCov = minCoverage != null ? Math.max(0.0, minCoverage) : 0.0;

		long tick = client.level.getGameTime();
		Vec3 eyePos = player.getEyePosition();
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		float fov = (float) client.options.fov().get();

		ViewBlocksDto.CameraInfo camera = new ViewBlocksDto.CameraInfo(
				new double[]{eyePos.x, eyePos.y, eyePos.z},
				yaw,
				pitch,
				fov
		);

		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
		double fy = -Math.sin(pitchRad);
		double fz = Math.cos(yawRad) * Math.cos(pitchRad);
		Vec3 forward = new Vec3(fx, fy, fz).normalize();
		Vec3 right = new Vec3(Math.cos(yawRad), 0, Math.sin(yawRad)).normalize();
		Vec3 up = right.cross(forward).normalize();

		double aspect = (double) width / (double) height;
		double tanHalfFov = Math.tan(Math.toRadians(fov / 2.0));

		List<String> palette = new ArrayList<>();
		Map<String, Integer> paletteIndices = new HashMap<>();

		record BlockRecord(int x, int y, int z, int paletteIndex, Set<String> faces, double minDistance, int[] hitCount) {}
		Map<Long, BlockRecord> hitBlocks = new HashMap<>();

		int totalRays = width * height;
		int skyRays = 0;
		List<Integer> labelsList = Boolean.TRUE.equals(grid) ? new ArrayList<>(totalRays) : null;
		List<Double> depthList = Boolean.TRUE.equals(grid) ? new ArrayList<>(totalRays) : null;

		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

		for (int row = 0; row < height; row++) {
			double v = (1.0 - 2.0 * (row + 0.5) / height) * tanHalfFov;
			for (int col = 0; col < width; col++) {
				double u = (2.0 * (col + 0.5) / width - 1.0) * aspect * tanHalfFov;
				Vec3 dir = forward.add(right.scale(u)).add(up.scale(v)).normalize();

				double ox = eyePos.x;
				double oy = eyePos.y;
				double oz = eyePos.z;

				int mapX = (int) Math.floor(ox);
				int mapY = (int) Math.floor(oy);
				int mapZ = (int) Math.floor(oz);

				double deltaDistX = Math.abs(1.0 / (dir.x == 0 ? 1e-9 : dir.x));
				double deltaDistY = Math.abs(1.0 / (dir.y == 0 ? 1e-9 : dir.y));
				double deltaDistZ = Math.abs(1.0 / (dir.z == 0 ? 1e-9 : dir.z));

				int stepX;
				double sideDistX;
				if (dir.x < 0) {
					stepX = -1;
					sideDistX = (ox - mapX) * deltaDistX;
				} else {
					stepX = 1;
					sideDistX = (mapX + 1.0 - ox) * deltaDistX;
				}

				int stepY;
				double sideDistY;
				if (dir.y < 0) {
					stepY = -1;
					sideDistY = (oy - mapY) * deltaDistY;
				} else {
					stepY = 1;
					sideDistY = (mapY + 1.0 - oy) * deltaDistY;
				}

				int stepZ;
				double sideDistZ;
				if (dir.z < 0) {
					stepZ = -1;
					sideDistZ = (oz - mapZ) * deltaDistZ;
				} else {
					stepZ = 1;
					sideDistZ = (mapZ + 1.0 - oz) * deltaDistZ;
				}

				boolean hitOpaque = false;
				double hitDist = maxDist;
				int primaryPaletteIdx = -1;

				while (!hitOpaque) {
					int side;
					if (sideDistX < sideDistY) {
						if (sideDistX < sideDistZ) {
							sideDistX += deltaDistX;
							mapX += stepX;
							side = 0;
						} else {
							sideDistZ += deltaDistZ;
							mapZ += stepZ;
							side = 2;
						}
					} else {
						if (sideDistY < sideDistZ) {
							sideDistY += deltaDistY;
							mapY += stepY;
							side = 1;
						} else {
							sideDistZ += deltaDistZ;
							mapZ += stepZ;
							side = 2;
						}
					}

					double currentDist = switch (side) {
						case 0 -> sideDistX - deltaDistX;
						case 1 -> sideDistY - deltaDistY;
						default -> sideDistZ - deltaDistZ;
					};

					if (currentDist > maxDist) break;

					mutablePos.set(mapX, mapY, mapZ);
					if (!client.level.hasChunkAt(mutablePos)) break;

					BlockState state = client.level.getBlockState(mutablePos);
					if (state.isAir()) continue;

					boolean isLiquid = !state.getFluidState().isEmpty();
					if (isLiquid && !includeFluids) continue;

					boolean occludes = state.canOcclude() && !isLiquid;

					String faceName = switch (side) {
						case 0 -> stepX < 0 ? "east" : "west";
						case 1 -> stepY < 0 ? "up" : "down";
						default -> stepZ < 0 ? "south" : "north";
					};

					String stateString = BlockStateSerializer.toString(state);
					int pIdx = paletteIndices.computeIfAbsent(stateString, s -> {
						palette.add(s);
						return palette.size() - 1;
					});

					long packed = BlockPos.asLong(mapX, mapY, mapZ);
					BlockRecord rec = hitBlocks.get(packed);
					if (rec == null) {
						Set<String> faces = new HashSet<>();
						faces.add(faceName);
						rec = new BlockRecord(mapX, mapY, mapZ, pIdx, faces, currentDist, new int[]{1});
						hitBlocks.put(packed, rec);
					} else {
						rec.faces.add(faceName);
						rec.hitCount[0]++;
					}

					if (primaryPaletteIdx == -1) {
						primaryPaletteIdx = pIdx;
						hitDist = currentDist;
					}

					if (occludes || !allowPassThrough) {
						hitOpaque = true;
						break;
					}
				}

				if (!hitOpaque && primaryPaletteIdx == -1) {
					skyRays++;
					if (labelsList != null) labelsList.add(-1);
					if (depthList != null) depthList.add(maxDist);
				} else {
					if (labelsList != null) labelsList.add(primaryPaletteIdx);
					if (depthList != null) depthList.add(hitDist);
				}
			}
		}

		double skyFraction = (double) skyRays / totalRays;

		List<ViewBlocksDto.BlockHit> blockHits = new ArrayList<>();
		for (BlockRecord rec : hitBlocks.values()) {
			double coverage = (double) rec.hitCount[0] / totalRays;
			if (coverage >= minCov) {
				blockHits.add(new ViewBlocksDto.BlockHit(
						new int[]{rec.x, rec.y, rec.z},
						rec.paletteIndex,
						new ArrayList<>(rec.faces),
						Math.round(rec.minDistance * 10.0) / 10.0,
						Math.round(coverage * 10000.0) / 10000.0
				));
			}
		}

		blockHits.sort((a, b) -> Double.compare(b.coverage(), a.coverage()));

		return new ViewBlocksDto.Response(
				tick,
				camera,
				new int[]{width, height},
				palette,
				blockHits,
				List.of(),
				Math.round(skyFraction * 1000.0) / 1000.0,
				labelsList,
				depthList
		);
	}

	@Override
	public ViewBlocksDto.SummaryResponse getViewBlocksSummary(Double maxDistance) {
		ViewBlocksDto.Response view = getViewBlocks("64x36", maxDistance, true, true, false, false, 0.0);
		Map<String, Integer> counts = new HashMap<>();
		Map<String, ViewBlocksDto.NearestBlock> nearest = new HashMap<>();

		for (ViewBlocksDto.BlockHit b : view.blocks()) {
			String state = view.palette().get(b.p());
			String blockId = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
			counts.put(blockId, counts.getOrDefault(blockId, 0) + 1);

			ViewBlocksDto.NearestBlock currNearest = nearest.get(blockId);
			if (currNearest == null || b.distance() < currNearest.distance()) {
				nearest.put(blockId, new ViewBlocksDto.NearestBlock(b.pos(), b.distance()));
			}
		}

		RaycastDto crosshair = raycast(null, null, maxDistance, true, true);

		return new ViewBlocksDto.SummaryResponse(
				view.tick(),
				view.camera(),
				counts,
				nearest,
				crosshair
		);
	}

	@Override
	public HudDto.Response getHud(java.util.Set<String> include) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) throw Problems.noPlayer();
		LocalPlayer player = client.player;
		long tick = client.level != null ? client.level.getGameTime() : 0L;

		boolean all = include == null || include.isEmpty();

		String actionBar = null;
		HudDto.TitleInfo titleInfo = null;
		List<HudDto.BossBarInfo> bossBars = null;
		HudDto.TabList tabList = null;
		List<HudDto.SubtitleEntry> subtitles = null;

		if (client.gui instanceof GuiAccessor guiAccessor) {
			net.minecraft.network.chat.Component actionMsg = guiAccessor.getOverlayMessageString();
			if (actionMsg != null && guiAccessor.getOverlayMessageTime() > 0) {
				actionBar = TextSerializer.toPlainText(actionMsg);
			}

			if (all || include.contains("title")) {
				net.minecraft.network.chat.Component titleComp = guiAccessor.getTitle();
				net.minecraft.network.chat.Component subComp = guiAccessor.getSubtitle();
				if (titleComp != null || subComp != null) {
					titleInfo = new HudDto.TitleInfo(
							titleComp != null ? TextSerializer.toPlainText(titleComp) : null,
							subComp != null ? TextSerializer.toPlainText(subComp) : null,
							guiAccessor.getTitleFadeInTime(),
							guiAccessor.getTitleStayTime(),
							guiAccessor.getTitleFadeOutTime()
					);
				}
			}

			if (all || include.contains("bossbars")) {
				bossBars = new ArrayList<>();
				if (client.gui.getBossOverlay() instanceof BossHealthOverlayAccessor bossAccessor) {
					Map<UUID, net.minecraft.client.gui.components.LerpingBossEvent> events = bossAccessor.getEvents();
					if (events != null) {
						for (Map.Entry<UUID, net.minecraft.client.gui.components.LerpingBossEvent> entry : events.entrySet()) {
							net.minecraft.client.gui.components.LerpingBossEvent ev = entry.getValue();
							bossBars.add(new HudDto.BossBarInfo(
									entry.getKey().toString(),
									TextSerializer.toPlainText(ev.getName()),
									ev.getProgress(),
									ev.getColor().getName(),
									ev.getOverlay().getName(),
									ev.shouldDarkenScreen(),
									ev.shouldPlayBossMusic(),
									ev.shouldCreateWorldFog()
							));
						}
					}
				}
			}

			if (all || include.contains("tablist")) {
				String header = null;
				String footer = null;
				if (client.gui.getTabList() instanceof PlayerTabOverlayAccessor tabAccessor) {
					if (tabAccessor.getHeader() != null) header = TextSerializer.toPlainText(tabAccessor.getHeader());
					if (tabAccessor.getFooter() != null) footer = TextSerializer.toPlainText(tabAccessor.getFooter());
				}
				List<HudDto.TabList.PlayerEntry> players = new ArrayList<>();
				if (client.getConnection() != null) {
					for (net.minecraft.client.multiplayer.PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
						players.add(new HudDto.TabList.PlayerEntry(
								info.getProfile().getName(),
								info.getProfile().getId().toString(),
								info.getLatency(),
								info.getGameMode() != null ? info.getGameMode().getName() : "unknown",
								info.getTabListDisplayName() != null ? TextSerializer.toPlainText(info.getTabListDisplayName()) : null
						));
					}
				}
				tabList = new HudDto.TabList(header, footer, players);
			}

			if (all || include.contains("subtitles")) {
				subtitles = new ArrayList<>();
				if (guiAccessor.getSubtitleOverlay() instanceof SubtitleOverlayAccessor subAccessor) {
					List<?> subList = subAccessor.getSubtitles();
					if (subList != null) {
						for (Object item : subList) {
							if (item != null) {
								try {
									java.lang.reflect.Method getTextMethod = item.getClass().getMethod("getText");
									Object textObj = getTextMethod.invoke(item);
									if (textObj instanceof net.minecraft.network.chat.Component comp) {
										subtitles.add(new HudDto.SubtitleEntry(
												TextSerializer.toPlainText(comp),
												1.0,
												null
										));
									}
								} catch (Exception ignored) {}
							}
						}
					}
				}
			}
		}

		HudDto.ScoreboardSidebar scoreboard = null;
		if (all || include.contains("scoreboard")) {
			if (client.level != null) {
				net.minecraft.world.scores.Scoreboard sb = client.level.getScoreboard();
				net.minecraft.world.scores.Objective obj = sb.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
				if (obj != null) {
					List<HudDto.ScoreboardSidebar.ScoreEntry> scoreEntries = new ArrayList<>();
					for (net.minecraft.world.scores.PlayerScoreEntry pse : sb.listPlayerScores(obj)) {
						if (!pse.isHidden()) {
							scoreEntries.add(new HudDto.ScoreboardSidebar.ScoreEntry(
									pse.owner(),
									pse.value(),
									pse.display() != null ? TextSerializer.toPlainText(pse.display()) : Integer.toString(pse.value())
							));
						}
					}
					scoreboard = new HudDto.ScoreboardSidebar(
							obj.getName(),
							TextSerializer.toPlainText(obj.getDisplayName()),
							scoreEntries
					);
				}
			}
		}

		HudDto.HeldItemInfo heldItem = null;
		if (all || include.contains("helditem")) {
			ItemStack main = player.getMainHandItem();
			ItemStack off = player.getOffhandItem();
			int selected = player.getInventory().selected;
			heldItem = new HudDto.HeldItemInfo(
					!main.isEmpty() ? ItemSerializer.toSlotInfo(selected, main) : null,
					!off.isEmpty() ? ItemSerializer.toSlotInfo(40, off) : null,
					selected
			);
		}

		List<HudDto.ToastInfo> toasts = null;
		if (all || include.contains("toasts")) {
			toasts = new ArrayList<>();
			if (client.getToastManager() instanceof ToastManagerAccessor toastAccessor) {
				List<?> visible = toastAccessor.getVisibleToasts();
				if (visible != null) {
					for (Object toastInst : visible) {
						if (toastInst != null) {
							toasts.add(new HudDto.ToastInfo(
									toastInst.getClass().getSimpleName(),
									"Notification",
									null
							));
						}
					}
				}
			}
		}

		return new HudDto.Response(
				tick,
				actionBar,
				titleInfo,
				bossBars,
				scoreboard,
				tabList,
				subtitles,
				heldItem,
				toasts
		);
	}

	@Override
	public CameraDto.Response getCamera() {
		Minecraft client = Minecraft.getInstance();
		CameraType type = client.options.getCameraType();
		String perspective = switch (type) {
			case FIRST_PERSON -> "first_person";
			case THIRD_PERSON_BACK -> "third_person_back";
			case THIRD_PERSON_FRONT -> "third_person_front";
		};

		double fov = client.options.fov().get();
		double[] eyePos = client.player != null
				? new double[]{client.player.getEyePosition().x, client.player.getEyePosition().y, client.player.getEyePosition().z}
				: new double[]{0, 0, 0};
		CameraDto.Rotation rot = client.player != null
				? new CameraDto.Rotation(client.player.getYRot(), client.player.getXRot())
				: new CameraDto.Rotation(0, 0);

		return new CameraDto.Response(perspective, fov, eyePos, rot);
	}

	@Override
	public CameraDto.Response updateCamera(CameraDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		if (request.perspective() != null) {
			String p = request.perspective().trim().toLowerCase();
			CameraType targetType = switch (p) {
				case "first_person" -> CameraType.FIRST_PERSON;
				case "third_person_back" -> CameraType.THIRD_PERSON_BACK;
				case "third_person_front" -> CameraType.THIRD_PERSON_FRONT;
				default -> throw Problems.invalidParameter("perspective", "Must be one of: first_person, third_person_back, third_person_front");
			};
			client.options.setCameraType(targetType);
		}

		if (request.fov() != null) {
			int clampedFov = (int) Math.round(Math.max(30.0, Math.min(110.0, request.fov())));
			client.options.fov().set(clampedFov);
		}

		return getCamera();
	}

	@Override
	public WindowDto.Response getWindow() {
		Minecraft client = Minecraft.getInstance();
		Window w = client.getWindow();
		return new WindowDto.Response(
				w.getWidth(),
				w.getHeight(),
				w.getGuiScaledWidth(),
				w.getGuiScaledHeight(),
				w.getGuiScale(),
				w.isFullscreen(),
				client.isWindowActive()
		);
	}

	@Override
	public WindowDto.Response updateWindow(WindowDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		if (request.fullscreen() != null) {
			if (request.fullscreen() != client.getWindow().isFullscreen()) {
				client.getWindow().toggleFullScreen();
				client.options.fullscreen().set(request.fullscreen());
			}
		}
		return getWindow();
	}

	@Override
	public DebugDto.Response getDebug() {
		Minecraft client = Minecraft.getInstance();
		List<String> left = List.of();
		List<String> right = List.of();

		if (client.gui != null && client.gui.getDebugOverlay() instanceof DebugScreenOverlayAccessor dbgAccessor) {
			try {
				if (client.level != null && client.getConnection() != null) {
					left = dbgAccessor.invokeGetGameInformation();
				}
				right = dbgAccessor.invokeGetSystemInformation();
			} catch (Exception ignored) {
			}
		}

		boolean hitboxes = client.getEntityRenderDispatcher().shouldRenderHitBoxes();
		boolean chunkBorders = false;
		if (client.debugRenderer instanceof DebugRendererAccessor dra) {
			chunkBorders = dra.getRenderChunkborder();
		}

		return new DebugDto.Response(left, right, hitboxes, chunkBorders);
	}

	@Override
	public DebugDto.Response updateDebug(DebugDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		if (request.hitboxes() != null) {
			client.getEntityRenderDispatcher().setRenderHitBoxes(request.hitboxes());
		}
		if (request.chunkBorders() != null) {
			if (client.debugRenderer instanceof DebugRendererAccessor dra) {
				dra.setRenderChunkborder(request.chunkBorders());
			}
		}
		return getDebug();
	}

	@Override
	public KeybindsDto.Response getKeybinds() {
		Minecraft client = Minecraft.getInstance();
		List<KeybindsDto.KeyMappingDetail> list = new ArrayList<>();
		for (KeyMapping km : client.options.keyMappings) {
			list.add(new KeybindsDto.KeyMappingDetail(
					km.getName(),
					km.getCategory(),
					km.getTranslatedKeyMessage().getString(),
					km.getDefaultKey().getName(),
					km.isDefault(),
					km.isUnbound()
			));
		}
		return new KeybindsDto.Response(list.size(), list);
	}

	@Override
	public KeybindsDto.Response updateKeybinds(KeybindsDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		if (Boolean.TRUE.equals(request.resetAll())) {
			for (KeyMapping km : client.options.keyMappings) {
				km.setKey(km.getDefaultKey());
			}
			KeyMapping.resetMapping();
			client.options.save();
		}

		if (request.resetKeys() != null) {
			for (String keyName : request.resetKeys()) {
				for (KeyMapping km : client.options.keyMappings) {
					if (km.getName().equalsIgnoreCase(keyName)) {
						km.setKey(km.getDefaultKey());
					}
				}
			}
			KeyMapping.resetMapping();
			client.options.save();
		}

		if (request.rebinds() != null) {
			for (Map.Entry<String, String> entry : request.rebinds().entrySet()) {
				for (KeyMapping km : client.options.keyMappings) {
					if (km.getName().equalsIgnoreCase(entry.getKey())) {
						if ("none".equalsIgnoreCase(entry.getValue()) || entry.getValue().isBlank()) {
							km.setKey(InputConstants.UNKNOWN);
						} else {
							InputConstants.Key k = InputConstants.getKey(entry.getValue());
							km.setKey(k);
						}
					}
				}
			}
			KeyMapping.resetMapping();
			client.options.save();
		}

		return getKeybinds();
	}

	@Override
	public ResourcePacksDto.Response getResourcePacks() {
		Minecraft client = Minecraft.getInstance();
		PackRepository repo = client.getResourcePackRepository();
		List<ResourcePacksDto.PackInfo> available = new ArrayList<>();
		for (Pack pack : repo.getAvailablePacks()) {
			available.add(new ResourcePacksDto.PackInfo(
					pack.getId(),
					TextSerializer.toPlainText(pack.getTitle()),
					TextSerializer.toPlainText(pack.getDescription()),
					pack.isRequired(),
					pack.isFixedPosition(),
					pack.getCompatibility().name()
			));
		}
		List<String> enabled = new ArrayList<>(repo.getSelectedIds());
		return new ResourcePacksDto.Response(available, enabled);
	}

	@Override
	public ResourcePacksDto.Response updateResourcePacks(ResourcePacksDto.Request request) {
		Minecraft client = Minecraft.getInstance();
		PackRepository repo = client.getResourcePackRepository();
		if (request.enabled() != null) {
			repo.setSelected(request.enabled());
			client.options.resourcePacks.clear();
			client.options.resourcePacks.addAll(request.enabled());
			client.options.save();
		}
		if (Boolean.TRUE.equals(request.reload()) || request.enabled() != null) {
			client.reloadResourcePacks();
		}
		return getResourcePacks();
	}

	@Override
	public StatsDto.Response getStats(String category) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		StatsCounter statsCounter = client.player.getStats();
		Object2IntMap<Stat<?>> map = ((StatsCounterAccessor) statsCounter).getStats();

		Map<String, Integer> custom = new LinkedHashMap<>();
		Map<String, Integer> mined = new LinkedHashMap<>();
		Map<String, Integer> crafted = new LinkedHashMap<>();
		Map<String, Integer> used = new LinkedHashMap<>();
		Map<String, Integer> broken = new LinkedHashMap<>();
		Map<String, Integer> killed = new LinkedHashMap<>();
		Map<String, Integer> killedBy = new LinkedHashMap<>();

		for (Object2IntMap.Entry<Stat<?>> entry : map.object2IntEntrySet()) {
			Stat<?> stat = entry.getKey();
			int val = entry.getIntValue();
			if (stat.getType() == Stats.CUSTOM) {
				custom.put(((ResourceLocation) stat.getValue()).toString(), val);
			} else if (stat.getType() == Stats.BLOCK_MINED) {
				mined.put(BuiltInRegistries.BLOCK.getKey((Block) stat.getValue()).toString(), val);
			} else if (stat.getType() == Stats.ITEM_CRAFTED) {
				crafted.put(BuiltInRegistries.ITEM.getKey((Item) stat.getValue()).toString(), val);
			} else if (stat.getType() == Stats.ITEM_USED) {
				used.put(BuiltInRegistries.ITEM.getKey((Item) stat.getValue()).toString(), val);
			} else if (stat.getType() == Stats.ITEM_BROKEN) {
				broken.put(BuiltInRegistries.ITEM.getKey((Item) stat.getValue()).toString(), val);
			} else if (stat.getType() == Stats.ENTITY_KILLED) {
				killed.put(BuiltInRegistries.ENTITY_TYPE.getKey((EntityType<?>) stat.getValue()).toString(), val);
			} else if (stat.getType() == Stats.ENTITY_KILLED_BY) {
				killedBy.put(BuiltInRegistries.ENTITY_TYPE.getKey((EntityType<?>) stat.getValue()).toString(), val);
			}
		}

		if (category != null && !category.isBlank()) {
			String cat = category.toLowerCase().trim();
			return switch (cat) {
				case "custom" -> new StatsDto.Response(custom, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
				case "mined" -> new StatsDto.Response(Map.of(), mined, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
				case "crafted" -> new StatsDto.Response(Map.of(), Map.of(), crafted, Map.of(), Map.of(), Map.of(), Map.of());
				case "used" -> new StatsDto.Response(Map.of(), Map.of(), Map.of(), used, Map.of(), Map.of(), Map.of());
				case "broken" -> new StatsDto.Response(Map.of(), Map.of(), Map.of(), Map.of(), broken, Map.of(), Map.of());
				case "killed" -> new StatsDto.Response(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), killed, Map.of());
				case "killed_by" -> new StatsDto.Response(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), killedBy);
				default -> throw Problems.invalidParameter("category", "Must be one of: custom, mined, crafted, used, broken, killed, killed_by");
			};
		}

		return new StatsDto.Response(custom, mined, crafted, used, broken, killed, killedBy);
	}

	@Override
	public AdvancementsDto.Response getAdvancements(Boolean done, String search) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.getConnection() == null) {
			throw Problems.noPlayer();
		}
		ClientAdvancements clientAdvancements = client.getConnection().getAdvancements();
		Map<AdvancementHolder, AdvancementProgress> progressMap = ((ClientAdvancementsAccessor) clientAdvancements).getProgress();

		List<AdvancementsDto.AdvancementInfo> list = new ArrayList<>();
		for (Map.Entry<AdvancementHolder, AdvancementProgress> entry : progressMap.entrySet()) {
			AdvancementHolder holder = entry.getKey();
			AdvancementProgress progress = entry.getValue();
			String id = holder.id().toString();
			Advancement adv = holder.value();

			String title = id;
			String desc = "";
			String type = "task";
			String icon = "";
			if (adv.display().isPresent()) {
				DisplayInfo d = adv.display().get();
				title = TextSerializer.toPlainText(d.getTitle());
				desc = TextSerializer.toPlainText(d.getDescription());
				type = d.getType().name().toLowerCase();
				icon = BuiltInRegistries.ITEM.getKey(d.getIcon().getItem()).toString();
			}

			String parent = adv.parent().map(ResourceLocation::toString).orElse(null);
			boolean isDone = progress.isDone();
			float percent = progress.getPercent();

			List<String> completedCriteria = new ArrayList<>();
			for (String c : progress.getCompletedCriteria()) {
				completedCriteria.add(c);
			}
			List<String> remainingCriteria = new ArrayList<>();
			for (String c : progress.getRemainingCriteria()) {
				remainingCriteria.add(c);
			}

			if (done != null && isDone != done) {
				continue;
			}
			if (search != null && !search.isBlank()) {
				String term = search.toLowerCase();
				if (!id.toLowerCase().contains(term) && !title.toLowerCase().contains(term)) {
					continue;
				}
			}

			list.add(new AdvancementsDto.AdvancementInfo(
					id, title, desc, type, icon, parent, isDone, percent, completedCriteria, remainingCriteria
			));
		}

		int completed = (int) list.stream().filter(AdvancementsDto.AdvancementInfo::done).count();
		return new AdvancementsDto.Response(list.size(), completed, list);
	}

	@Override
	public RecipesDto.Response getRecipes(Boolean craftable, String category, String search) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		ClientRecipeBook recipeBook = client.player.getRecipeBook();
		ContextMap context = client.level != null ? SlotDisplayContext.fromLevel(client.level) : null;

		List<RecipesDto.RecipeInfo> list = new ArrayList<>();
		for (RecipeCollection collection : recipeBook.getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				int id = entry.id().index();
				String cat = BuiltInRegistries.RECIPE_BOOK_CATEGORY.getKey(entry.category()).toString();
				RecipeDisplay display = entry.display();
				ItemStack resultStack = context != null ? display.result().resolveForFirstStack(context) : ItemStack.EMPTY;
				String resultId = resultStack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(resultStack.getItem()).toString();
				int resultCount = resultStack.getCount();
				boolean isCraftable = collection.isCraftable(entry.id());

				List<String> ingredients = extractRecipeIngredients(display, context);

				if (craftable != null && isCraftable != craftable) {
					continue;
				}
				if (category != null && !category.isBlank()) {
					if (!cat.equalsIgnoreCase(category.trim()) && !cat.endsWith(":" + category.trim())) {
						continue;
					}
				}
				if (search != null && !search.isBlank()) {
					String term = search.toLowerCase();
					if (!resultId.toLowerCase().contains(term)) {
						continue;
					}
				}

				list.add(new RecipesDto.RecipeInfo(
						id,
						cat,
						new RecipesDto.ResultItem(resultId, resultCount),
						isCraftable,
						ingredients
				));
			}
		}

		return new RecipesDto.Response(list.size(), list);
	}

	private List<String> extractRecipeIngredients(RecipeDisplay display, ContextMap context) {
		if (context == null) return List.of();
		List<String> ingredients = new ArrayList<>();
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			for (SlotDisplay sd : shaped.ingredients()) {
				ItemStack is = sd.resolveForFirstStack(context);
				if (!is.isEmpty()) {
					ingredients.add(BuiltInRegistries.ITEM.getKey(is.getItem()).toString());
				}
			}
		} else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			for (SlotDisplay sd : shapeless.ingredients()) {
				ItemStack is = sd.resolveForFirstStack(context);
				if (!is.isEmpty()) {
					ingredients.add(BuiltInRegistries.ITEM.getKey(is.getItem()).toString());
				}
			}
		} else if (display instanceof FurnaceRecipeDisplay furnace) {
			ItemStack is = furnace.ingredient().resolveForFirstStack(context);
			if (!is.isEmpty()) {
				ingredients.add(BuiltInRegistries.ITEM.getKey(is.getItem()).toString());
			}
		} else if (display instanceof StonecutterRecipeDisplay stonecutter) {
			ItemStack is = stonecutter.input().resolveForFirstStack(context);
			if (!is.isEmpty()) {
				ingredients.add(BuiltInRegistries.ITEM.getKey(is.getItem()).toString());
			}
		} else if (display instanceof SmithingRecipeDisplay smithing) {
			ItemStack b = smithing.base().resolveForFirstStack(context);
			if (!b.isEmpty()) {
				ingredients.add(BuiltInRegistries.ITEM.getKey(b.getItem()).toString());
			}
			ItemStack a = smithing.addition().resolveForFirstStack(context);
			if (!a.isEmpty()) {
				ingredients.add(BuiltInRegistries.ITEM.getKey(a.getItem()).toString());
			}
		}
		return ingredients;
	}

	@Override
	public RecipesDto.PlaceResponse placeRecipe(RecipesDto.PlaceRequest request) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			throw Problems.noPlayer();
		}
		if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
			throw Problems.noContainer();
		}
		int containerId = containerScreen.getMenu().containerId;
		boolean max = Boolean.TRUE.equals(request.useMaxItems());
		if (client.gameMode != null) {
			client.gameMode.handlePlaceRecipe(containerId, new RecipeDisplayId(request.recipeId()), max);
		}
		return new RecipesDto.PlaceResponse(true, request.recipeId(), containerId);
	}

	// --- Phase 4: Session, Worlds, Servers ---

	@Override
	public SessionDto.Response getSession() {
		Minecraft client = Minecraft.getInstance();
		String state = "menu";
		if (client.level != null) {
			state = client.isLocalServer() ? "singleplayer" : "multiplayer";
		}
		boolean paused = client.isPaused();
		boolean lan = client.hasSingleplayerServer() && client.getSingleplayerServer().isPublished();
		Integer lanPort = lan ? client.getSingleplayerServer().getPort() : null;
		String serverAddr = client.getCurrentServer() != null ? client.getCurrentServer().ip : null;
		String serverName = client.getCurrentServer() != null ? client.getCurrentServer().name : null;
		String worldName = client.hasSingleplayerServer() ? client.getSingleplayerServer().getWorldData().getLevelName() : null;

		return new SessionDto.Response(state, paused, lan, lanPort, serverAddr, serverName, worldName);
	}

	@Override
	public SessionDto.Response setSessionPaused(Boolean paused) {
		Minecraft client = Minecraft.getInstance();
		if (Boolean.TRUE.equals(paused)) {
			client.pauseGame(false);
		} else if (Boolean.FALSE.equals(paused)) {
			if (client.screen instanceof PauseScreen) {
				client.setScreen(null);
			}
		}
		return getSession();
	}

	@Override
	public SessionDto.DisconnectResponse disconnectSession() {
		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
				client.execute(() -> {
					if (client.level != null) {
						boolean isLocal = client.isLocalServer();
						client.level.disconnect();
						if (isLocal) {
							client.disconnect(new GenericMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
							client.setScreen(new TitleScreen());
						} else {
							client.disconnect();
							client.setScreen(new TitleScreen());
						}
					}
				});
			});
		}
		return new SessionDto.DisconnectResponse(true);
	}

	@Override
	public SessionDto.LanResponse openSessionToLan(SessionDto.LanRequest request) {
		Minecraft client = Minecraft.getInstance();
		if (!client.hasSingleplayerServer()) {
			throw Problems.noWorld();
		}
		int port = (request != null && request.port() != null && request.port() > 0) ? request.port() : HttpUtil.getAvailablePort();
		GameType mode = GameType.SURVIVAL;
		if (request != null && request.gameMode() != null) {
			String m = request.gameMode().trim().toLowerCase();
			mode = switch (m) {
				case "creative" -> GameType.CREATIVE;
				case "spectator" -> GameType.SPECTATOR;
				case "adventure" -> GameType.ADVENTURE;
				default -> GameType.SURVIVAL;
			};
		}
		boolean commands = request != null && Boolean.TRUE.equals(request.allowCommands());
		boolean published = client.getSingleplayerServer().publishServer(mode, commands, port);
		return new SessionDto.LanResponse(published, port);
	}

	@Override
	public WorldsDto.ListResponse getWorlds() {
		Minecraft client = Minecraft.getInstance();
		LevelStorageSource levelSource = client.getLevelSource();
		List<WorldsDto.WorldSummaryInfo> list = new ArrayList<>();
		try {
			LevelStorageSource.LevelCandidates candidates = levelSource.findLevelCandidates();
			List<LevelSummary> summaries = levelSource.loadLevelSummaries(candidates).join();
			for (LevelSummary s : summaries) {
				list.add(new WorldsDto.WorldSummaryInfo(
						s.getLevelId(),
						s.getLevelName(),
						s.getLastPlayed(),
						s.getGameMode().getName(),
						s.isHardcore(),
						s.hasCommands(),
						s.getWorldVersionName().getString(),
						s.isLocked()
				));
			}
		} catch (Exception e) {
			// Return whatever was collected
		}
		return new WorldsDto.ListResponse(list.size(), list);
	}

	@Override
	public WorldsDto.LoadResponse loadWorld(String levelId) {
		Minecraft client = Minecraft.getInstance();
		CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
			client.execute(() -> client.createWorldOpenFlows().openWorld(levelId, () -> {}));
		});
		return new WorldsDto.LoadResponse(true, levelId);
	}

	@Override
	public WorldsDto.DeleteResponse deleteWorld(String levelId) {
		Minecraft client = Minecraft.getInstance();
		try (LevelStorageSource.LevelStorageAccess access = client.getLevelSource().createAccess(levelId)) {
			access.deleteLevel();
		} catch (Exception e) {
			throw Problems.internal("Failed to delete world: " + e.getMessage());
		}
		return new WorldsDto.DeleteResponse(true, levelId);
	}

	@Override
	public ServersDto.ListResponse getServers() {
		Minecraft client = Minecraft.getInstance();
		ServerList serverList = new ServerList(client);
		serverList.load();
		List<ServersDto.ServerEntryInfo> list = new ArrayList<>();
		for (int i = 0; i < serverList.size(); i++) {
			ServerData sd = serverList.get(i);
			String motd = sd.motd != null ? sd.motd.getString() : "";
			String count = sd.players != null ? sd.players.online() + "/" + sd.players.max() : "0/0";
			list.add(new ServersDto.ServerEntryInfo(i, sd.name, sd.ip, sd.ping, motd, count));
		}
		return new ServersDto.ListResponse(list.size(), list);
	}

	@Override
	public ServersDto.ListResponse addServer(ServersDto.AddRequest request) {
		Minecraft client = Minecraft.getInstance();
		if (request.name() == null || request.address() == null) {
			throw Problems.invalidParameter("name/address", "Server name and address are required");
		}
		ServerList serverList = new ServerList(client);
		serverList.load();
		ServerData sd = new ServerData(request.name(), request.address(), ServerData.Type.OTHER);
		serverList.add(sd, false);
		serverList.save();
		return getServers();
	}

	@Override
	public ServersDto.ListResponse updateServer(int index, ServersDto.UpdateRequest request) {
		Minecraft client = Minecraft.getInstance();
		ServerList serverList = new ServerList(client);
		serverList.load();
		if (index < 0 || index >= serverList.size()) {
			throw Problems.notFound("Server not found at index " + index);
		}
		ServerData sd = serverList.get(index);
		if (request.name() != null) sd.name = request.name();
		if (request.address() != null) sd.ip = request.address();
		serverList.save();
		return getServers();
	}

	@Override
	public ServersDto.DeleteResponse deleteServer(int index) {
		Minecraft client = Minecraft.getInstance();
		ServerList serverList = new ServerList(client);
		serverList.load();
		if (index < 0 || index >= serverList.size()) {
			throw Problems.notFound("Server not found at index " + index);
		}
		ServerData sd = serverList.get(index);
		serverList.remove(sd);
		serverList.save();
		return new ServersDto.DeleteResponse(true, index);
	}

	@Override
	public ServersDto.ConnectResponse connectServer(String address) {
		Minecraft client = Minecraft.getInstance();
		if (address == null || address.isBlank()) {
			throw Problems.invalidParameter("address", "Server address must not be empty");
		}
		ServerAddress sa = ServerAddress.parseString(address);
		ServerData sd = new ServerData("Server", address, ServerData.Type.OTHER);
		CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
			client.execute(() -> ConnectScreen.startConnecting(new TitleScreen(), client, sa, sd, false, null));
		});
		return new ServersDto.ConnectResponse(true, address);
	}
}

