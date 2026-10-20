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

	// --- Structured View & HUD ---

	ViewBlocksDto.Response getViewBlocks(String res, Double maxDistance, Boolean fluids, Boolean passThrough, Boolean entities, Boolean grid, Double minCoverage);

	ViewBlocksDto.SummaryResponse getViewBlocksSummary(Double maxDistance);

	HudDto.Response getHud(java.util.Set<String> include);

	// --- Phase 2: Camera, Window, Debug, Keybinds, Resource Packs ---

	CameraDto.Response getCamera();

	CameraDto.Response updateCamera(CameraDto.Request request);

	WindowDto.Response getWindow();

	WindowDto.Response updateWindow(WindowDto.Request request);

	DebugDto.Response getDebug();

	DebugDto.Response updateDebug(DebugDto.Request request);

	KeybindsDto.Response getKeybinds();

	KeybindsDto.Response updateKeybinds(KeybindsDto.Request request);

	ResourcePacksDto.Response getResourcePacks();

	ResourcePacksDto.Response updateResourcePacks(ResourcePacksDto.Request request);

	// --- Phase 3: Stats, Advancements, Recipes ---

	StatsDto.Response getStats(String category);

	AdvancementsDto.Response getAdvancements(Boolean done, String search);

	RecipesDto.Response getRecipes(Boolean craftable, String category, String search);

	RecipesDto.PlaceResponse placeRecipe(RecipesDto.PlaceRequest request);

	// --- Phase 4: Session, Worlds, Servers ---

	SessionDto.Response getSession();

	SessionDto.Response setSessionPaused(Boolean paused);

	SessionDto.DisconnectResponse disconnectSession();

	SessionDto.LanResponse openSessionToLan(SessionDto.LanRequest request);

	WorldsDto.ListResponse getWorlds();

	WorldsDto.LoadResponse loadWorld(String levelId);

	WorldsDto.DeleteResponse deleteWorld(String levelId);

	ServersDto.ListResponse getServers();

	ServersDto.ListResponse addServer(ServersDto.AddRequest request);

	ServersDto.ListResponse updateServer(int index, ServersDto.UpdateRequest request);

	ServersDto.DeleteResponse deleteServer(int index);

	ServersDto.ConnectResponse connectServer(String address);

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

