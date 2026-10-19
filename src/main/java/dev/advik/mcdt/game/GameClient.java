package dev.advik.mcdt.game;

import dev.advik.mcdt.api.v1.dto.*;

public interface GameClient {
	ClientDto getClient();

	PlayerDto getPlayer();

	InventoryDto getInventory();

	WorldDto getWorld();

	BlocksDto getBlocks(int fromX, int fromY, int fromZ, int toX, int toY, int toZ);

	EntitiesDto getEntities(Double radius, String typeFilter, double[] center, Integer limit);

	EntitiesDto.CategoriesResponse getEntityCategories();

	RaycastDto raycast(double[] from, double[] direction, Double maxDistance, Boolean includeFluids, Boolean includeEntities);

	ScreenDto getScreen();

	RegistriesDto.ListResponse getRegistries();

	RegistriesDto.EntryResponse getRegistryEntries(String registryId, String query, int offset, int limit);

	OptionsDto getOptions();

	byte[] captureScreenshot(float scale);

	PlayerSkinDto getPlayerSkinInfo(String targetPlayer, boolean embedImage);

	byte[] getPlayerSkinImage(String targetPlayer);

	TextureAtlasesDto getLoadedAtlases();

	TextureAtlasDto getAtlasDetails(String atlasId, boolean embedImage);

	byte[] getAtlasImage(String atlasId);

	// --- Milestone M2 Actions ---

	InputKeyDto.Response pressKey(InputKeyDto.Request request);

	InputMouseDto.Response mouseInput(InputMouseDto.Request request);

	ChatDto.SendResponse sendChat(ChatDto.SendRequest request);

	ChatDto.HistoryResponse getChatHistory(int offset, int limit);

	PlayerLookDto.Response setPlayerLook(PlayerLookDto.Request request);

	PlayerMoveDto.Response setPlayerMove(PlayerMoveDto.Request request);

	PlayerActionDto.Response performPlayerAction(PlayerActionDto.Request request);

	ScreenActionDto.Response performScreenAction(ScreenActionDto.Request request);

	ScreenSlotDto.Response clickScreenSlot(ScreenSlotDto.Request request);

	OptionsDto patchOptions(OptionsPatchDto request);

	void quitGame();

	// --- Milestone M3 Reflection Roots ---

	Object getRootObject(String name);

	java.util.Map<String, Object> getRootObjects();

	// --- Milestone M5 High-Level Control & Writes ---

	boolean isSingleplayer();

	boolean isMultiplayer();

	String getCurrentServerAddress();

	void sendPacket(Object packet);
}

