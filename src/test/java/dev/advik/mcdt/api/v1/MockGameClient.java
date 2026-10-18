package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.*;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problems;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MockGameClient implements GameClient {
	public boolean hasPlayer = true;
	public boolean hasWorld = true;
	public boolean isScreenOpen = true;

	@Override
	public ClientDto getClient() {
		return new ClientDto(
				144,
				new ClientDto.WindowInfo(1920, 1080, 960, 540, 2.0, false, true),
				new ClientDto.ScreenSummary(isScreenOpen, "net.minecraft.client.gui.screens.inventory.InventoryScreen", "Inventory"),
				hasWorld ? "singleplayer" : "menu",
				null
		);
	}

	@Override
	public PlayerDto getPlayer() {
		if (!hasPlayer) {
			throw Problems.noPlayer();
		}
		return new PlayerDto(
				new double[]{10.5, 64.0, -20.5},
				new PlayerDto.Rotation(90.0f, 0.0f),
				new double[]{0.0, 0.0, 0.0},
				20.0f,
				20.0f,
				0.0f,
				20,
				5.0f,
				new PlayerDto.Experience(5, 0.5f, 100),
				"survival",
				"standing",
				true,
				false,
				false,
				false,
				false,
				new PlayerDto.AirSupply(300, 300),
				List.of(new PlayerDto.EffectInfo("minecraft:speed", 1, 600, false, true)),
				new PlayerDto.Abilities(false, false, false, false)
		);
	}

	@Override
	public InventoryDto getInventory() {
		if (!hasPlayer) {
			throw Problems.noPlayer();
		}
		List<InventoryDto.SlotInfo> main = new ArrayList<>();
		main.add(new InventoryDto.SlotInfo(0, false, "minecraft:diamond_sword", 1, "Diamond Sword", 0, 1561, Map.of()));
		for (int i = 1; i < 36; i++) {
			main.add(InventoryDto.SlotInfo.empty(i));
		}
		List<InventoryDto.SlotInfo> armor = List.of(
				new InventoryDto.SlotInfo(36, false, "minecraft:iron_boots", 1, "Iron Boots", null, null, null),
				InventoryDto.SlotInfo.empty(37),
				InventoryDto.SlotInfo.empty(38),
				InventoryDto.SlotInfo.empty(39)
		);
		List<InventoryDto.SlotInfo> offhand = List.of(InventoryDto.SlotInfo.empty(40));

		return new InventoryDto(0, main, armor, offhand);
	}

	@Override
	public WorldDto getWorld() {
		if (!hasWorld) {
			throw Problems.noWorld();
		}
		return new WorldDto(
				"minecraft:overworld",
				new WorldDto.TimeInfo(1000L, 2000L, 0L, 1000L),
				new WorldDto.WeatherInfo(false, false, 0.0f, 0.0f),
				"normal",
				false,
				new WorldDto.SpawnInfo(0, 64, 0),
				256,
				12
		);
	}

	@Override
	public BlocksDto getBlocks(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
		if (!hasWorld) {
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

		return new BlocksDto(
				new BlocksDto.BlockBounds(new int[]{minX, minY, minZ}, new int[]{maxX, maxY, maxZ}, (int) volume),
				List.of("minecraft:stone", "minecraft:dirt"),
				List.of(new BlocksDto.BlockEntry(new int[]{minX, minY, minZ}, 0))
		);
	}

	@Override
	public EntitiesDto getEntities(Double radius, String typeFilter, double[] center, Integer limit) {
		if (!hasWorld) {
			throw Problems.noWorld();
		}
		double r = radius != null ? radius : 32.0;
		if (r <= 0 || r > 128.0) {
			throw Problems.invalidParameter("radius", "Radius must be between 0 and 128.");
		}
		int lim = limit != null ? limit : 100;
		if (lim <= 0 || lim > 500) {
			throw Problems.invalidParameter("limit", "Limit must be between 1 and 500.");
		}

		double[] c = center != null ? center : new double[]{10.5, 64.0, -20.5};

		EntitiesDto.EntityInfo entity = new EntitiesDto.EntityInfo(
				1,
				"00000000-0000-0000-0000-000000000001",
				"minecraft:pig",
				new double[]{12.0, 64.0, -20.0},
				new double[]{0.0, 0.0, 0.0},
				0.0f,
				0.0f,
				1.5,
				true,
				10.0f,
				10.0f,
				"Piggy",
				new EntitiesDto.BoundingBox(new double[]{11.5, 64.0, -20.5}, new double[]{12.5, 65.0, -19.5})
		);

		return new EntitiesDto(c, r, 1, List.of(entity));
	}

	@Override
	public RaycastDto raycast(double[] from, double[] direction, Double maxDistance, Boolean includeFluids, Boolean includeEntities) {
		if (!hasWorld) {
			throw Problems.noWorld();
		}
		return new RaycastDto(
				"block",
				new double[]{10.0, 63.0, -20.0},
				1.0,
				new RaycastDto.BlockHit(new int[]{10, 63, -20}, "up", "minecraft:grass_block", false),
				null
		);
	}

	@Override
	public ScreenDto getScreen() {
		if (!isScreenOpen) {
			return ScreenDto.closed();
		}
		return new ScreenDto(
				true,
				"net.minecraft.client.gui.screens.inventory.InventoryScreen",
				"Crafting",
				960,
				540,
				List.of(new ScreenDto.WidgetInfo(
						0,
						"button",
						"Recipe Book",
						new ScreenDto.WidgetBounds(100, 50, 20, 18),
						true,
						true,
						false
				)),
				new ScreenDto.ContainerInfo("minecraft:inventory", 46, List.of())
		);
	}

	@Override
	public RegistriesDto.ListResponse getRegistries() {
		return new RegistriesDto.ListResponse(2, List.of("minecraft:block", "minecraft:item"));
	}

	@Override
	public RegistriesDto.EntryResponse getRegistryEntries(String registryId, String query, int offset, int limit) {
		if (!"minecraft:item".equals(registryId) && !"item".equals(registryId)) {
			throw Problems.registryNotFound(registryId);
		}
		List<String> items = List.of("minecraft:diamond", "minecraft:diamond_sword", "minecraft:iron_ingot");
		if (query != null && !query.isBlank()) {
			items = items.stream().filter(i -> i.contains(query.toLowerCase())).toList();
		}
		int start = Math.max(0, Math.min(offset, items.size()));
		int end = Math.min(start + limit, items.size());
		List<String> page = items.subList(start, end);
		return new RegistriesDto.EntryResponse("minecraft:item", items.size(), offset, page.size(), page);
	}

	@Override
	public OptionsDto getOptions() {
		return new OptionsDto(
				70,
				12,
				10,
				2,
				1.0,
				120,
				false,
				true,
				"fancy",
				true,
				1.0,
				false,
				Map.of("master", 1.0f, "music", 0.5f),
				List.of(new OptionsDto.KeyMappingInfo("key.forward", "key.categories.movement", "W", "key.keyboard.w")),
				"en_us"
		);
	}

	@Override
	public byte[] captureScreenshot(float scale) {
		BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			ImageIO.write(img, "png", baos);
		} catch (IOException e) {
			throw Problems.screenshotFailed(e.getMessage());
		}
		return baos.toByteArray();
	}

	// --- Milestone M2 Mock Implementation ---

	public InputKeyDto.Request lastPressedKey;
	public InputMouseDto.Request lastMouseInput;
	public final List<String> sentMessages = new ArrayList<>();
	public final List<ChatDto.MessageEntry> chatHistory = new ArrayList<>(List.of(
			new ChatDto.MessageEntry("Welcome to Minecraft DevTools", "{\"text\":\"Welcome to Minecraft DevTools\"}", 100)
	));
	public float currentYaw = 90.0f;
	public float currentPitch = 0.0f;
	public PlayerMoveDto.Request lastMoveRequest;
	public PlayerActionDto.Request lastPlayerAction;
	public ScreenActionDto.Request lastScreenAction;
	public ScreenSlotDto.Request lastSlotClick;
	public boolean quitCalled = false;
	public int currentFov = 70;
	public int currentRenderDistance = 12;

	@Override
	public InputKeyDto.Response pressKey(InputKeyDto.Request request) {
		this.lastPressedKey = request;
		int keyCode = request.keyCode() != null ? request.keyCode() : 87;
		return new InputKeyDto.Response(true, request.action() != null ? request.action() : "tap",
				request.key() != null ? request.key() : "w", keyCode, 100L);
	}

	@Override
	public InputMouseDto.Response mouseInput(InputMouseDto.Request request) {
		this.lastMouseInput = request;
		double x = request.x() != null ? request.x() : 100.0;
		double y = request.y() != null ? request.y() : 100.0;
		return new InputMouseDto.Response(true, request.action() != null ? request.action() : "click", x, y, 100L);
	}

	@Override
	public ChatDto.SendResponse sendChat(ChatDto.SendRequest request) {
		if (!hasPlayer) {
			throw Problems.noPlayer();
		}
		String text = request.message() != null ? request.message() : request.command();
		if (text == null || text.isBlank()) {
			throw Problems.invalidParameter("message", "Message or command cannot be empty");
		}
		sentMessages.add(text);
		chatHistory.add(new ChatDto.MessageEntry(text, "{\"text\":\"" + text + "\"}", 101));
		boolean isCommand = request.command() != null || text.startsWith("/");
		return new ChatDto.SendResponse(true, isCommand ? "command" : "chat", text, 101L);
	}

	@Override
	public ChatDto.HistoryResponse getChatHistory(int offset, int limit) {
		int total = chatHistory.size();
		int start = Math.max(0, Math.min(offset, total));
		int end = Math.min(start + Math.max(1, limit), total);
		List<ChatDto.MessageEntry> page = chatHistory.subList(start, end);
		return new ChatDto.HistoryResponse(total, offset, page.size(), page);
	}

	@Override
	public PlayerLookDto.Response setPlayerLook(PlayerLookDto.Request request) {
		if (!hasPlayer) {
			throw Problems.noPlayer();
		}
		if (request.target() != null && request.target().length >= 3) {
			currentYaw = 45.0f;
			currentPitch = 10.0f;
		} else {
			if (request.yaw() != null) currentYaw = request.yaw();
			else if (request.deltaYaw() != null) currentYaw += request.deltaYaw();

			if (request.pitch() != null) currentPitch = request.pitch();
			else if (request.deltaPitch() != null) currentPitch += request.deltaPitch();
		}
		currentPitch = Math.max(-90.0f, Math.min(90.0f, currentPitch));
		return new PlayerLookDto.Response(currentYaw, currentPitch, 100L);
	}

	@Override
	public PlayerMoveDto.Response setPlayerMove(PlayerMoveDto.Request request) {
		if (!hasPlayer) {
			throw Problems.noPlayer();
		}
		this.lastMoveRequest = request;
		if (Boolean.TRUE.equals(request.stop())) {
			return new PlayerMoveDto.Response(false, false, false, false, false, false, false, false, 0, 100L);
		}
		boolean fwd = Boolean.TRUE.equals(request.forward());
		boolean back = Boolean.TRUE.equals(request.backward());
		boolean left = Boolean.TRUE.equals(request.left());
		boolean right = Boolean.TRUE.equals(request.right());
		boolean jump = Boolean.TRUE.equals(request.jump());
		boolean sneak = Boolean.TRUE.equals(request.sneak());
		boolean sprint = Boolean.TRUE.equals(request.sprint());
		int ticks = request.ticks() != null ? request.ticks() : 1;
		return new PlayerMoveDto.Response(fwd, back, left, right, jump, sneak, sprint, false, ticks, 100L);
	}

	@Override
	public PlayerActionDto.Response performPlayerAction(PlayerActionDto.Request request) {
		if (!hasPlayer) {
			throw Problems.noPlayer();
		}
		this.lastPlayerAction = request;
		String action = request.action() != null ? request.action() : "swing";
		return new PlayerActionDto.Response(action, true, "Mock action " + action + " executed", 100L);
	}

	@Override
	public ScreenActionDto.Response performScreenAction(ScreenActionDto.Request request) {
		if (!isScreenOpen) {
			throw Problems.noScreen();
		}
		this.lastScreenAction = request;
		if ("close".equals(request.action())) {
			isScreenOpen = false;
			return new ScreenActionDto.Response(true, "close", false, null, null, 100L);
		}
		return new ScreenActionDto.Response(true, request.action() != null ? request.action() : "click",
				true, "net.minecraft.client.gui.screens.inventory.InventoryScreen", "Inventory", 100L);
	}

	@Override
	public ScreenSlotDto.Response clickScreenSlot(ScreenSlotDto.Request request) {
		if (!isScreenOpen) {
			throw Problems.noScreen();
		}
		this.lastSlotClick = request;
		if (request.slot() < 0 || request.slot() > 45) {
			throw Problems.slotOutOfBounds(request.slot(), 46);
		}
		return new ScreenSlotDto.Response(request.slot(), request.mode() != null ? request.mode() : "pickup",
				InventoryDto.SlotInfo.empty(-1), InventoryDto.SlotInfo.empty(request.slot()), 100L);
	}

	@Override
	public OptionsDto patchOptions(OptionsPatchDto request) {
		if (request.fov() != null) currentFov = request.fov();
		if (request.renderDistance() != null) currentRenderDistance = request.renderDistance();
		return new OptionsDto(
				currentFov,
				currentRenderDistance,
				10,
				2,
				1.0,
				120,
				false,
				true,
				"fancy",
				true,
				1.0,
				false,
				Map.of("master", 1.0f, "music", 0.5f),
				List.of(new OptionsDto.KeyMappingInfo("key.forward", "key.categories.movement", "W", "key.keyboard.w")),
				"en_us"
		);
	}

	@Override
	public void quitGame() {
		this.quitCalled = true;
	}
}
