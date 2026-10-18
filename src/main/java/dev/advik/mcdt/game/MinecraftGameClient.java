package dev.advik.mcdt.game;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.advik.mcdt.api.v1.dto.*;
import dev.advik.mcdt.game.raycast.RaycastUtil;
import dev.advik.mcdt.game.serializers.BlockStateSerializer;
import dev.advik.mcdt.game.serializers.EntitySerializer;
import dev.advik.mcdt.game.serializers.ItemSerializer;
import dev.advik.mcdt.game.serializers.TextSerializer;
import dev.advik.mcdt.http.Problems;
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
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;

public final class MinecraftGameClient implements GameClient {
	private static final MinecraftGameClient INSTANCE = new MinecraftGameClient();

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
}
