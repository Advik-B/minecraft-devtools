package dev.advik.mcdt.game;

import dev.advik.mcdt.api.v1.dto.*;

public interface GameClient {
	ClientDto getClient();

	PlayerDto getPlayer();

	InventoryDto getInventory();

	WorldDto getWorld();

	BlocksDto getBlocks(int fromX, int fromY, int fromZ, int toX, int toY, int toZ);

	EntitiesDto getEntities(Double radius, String typeFilter, double[] center, Integer limit);

	RaycastDto raycast(double[] from, double[] direction, Double maxDistance, Boolean includeFluids, Boolean includeEntities);

	ScreenDto getScreen();

	RegistriesDto.ListResponse getRegistries();

	RegistriesDto.EntryResponse getRegistryEntries(String registryId, String query, int offset, int limit);

	OptionsDto getOptions();

	byte[] captureScreenshot(float scale);
}
