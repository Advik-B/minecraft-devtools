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
}
