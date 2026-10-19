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
}

