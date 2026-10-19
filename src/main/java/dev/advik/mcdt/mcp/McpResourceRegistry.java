package dev.advik.mcdt.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.advik.mcdt.api.v1.dto.ChatDto;
import dev.advik.mcdt.api.v1.dto.ClientDto;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.mcp.dto.McpProtocolDto.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Registry and reader for standard MCDT MCP resources.
 */
public final class McpResourceRegistry {
	public static final String URI_PLAYER = "mcdt://player";
	public static final String URI_INVENTORY = "mcdt://inventory";
	public static final String URI_SCREEN = "mcdt://screen";
	public static final String URI_WORLD = "mcdt://world";
	public static final String URI_HUD = "mcdt://hud";

	private final GameClient gameClient;

	public McpResourceRegistry(GameClient gameClient) {
		this.gameClient = gameClient;
	}

	public ResourceListResult listResources() {
		return new ResourceListResult(List.of(
				new ResourceDefinition(
						URI_PLAYER,
						"Player Telemetry",
						"Live position, rotation, motion, health, hunger, active effects, and abilities.",
						"application/json"
				),
				new ResourceDefinition(
						URI_INVENTORY,
						"Player Inventory",
						"All main inventory, hotbar, armor, and offhand slots with item IDs and counts.",
						"application/json"
				),
				new ResourceDefinition(
						URI_SCREEN,
						"Current Screen",
						"Active GUI screen class, title, interactive widget tree, and open container slots.",
						"application/json"
				),
				new ResourceDefinition(
						URI_WORLD,
						"World State",
						"Dimension, day time, weather state, loaded chunks, and spawn coordinates.",
						"application/json"
				),
				new ResourceDefinition(
						URI_HUD,
						"HUD & Chat Stream",
						"Action bar, title, subtitles, boss bars, and recent chat messages.",
						"application/json"
				)
		));
	}

	public CompletableFuture<ResourceReadResult> readResource(String uri, TaskQueue queue, long timeoutMs) {
		return queue.submit(() -> {
			Object payload;
			switch (uri) {
				case URI_PLAYER -> payload = gameClient.getPlayer();
				case URI_INVENTORY -> payload = gameClient.getInventory();
				case URI_SCREEN -> payload = gameClient.getScreen();
				case URI_WORLD -> payload = gameClient.getWorld();
				case URI_HUD -> {
					ClientDto client = gameClient.getClient();
					ChatDto.HistoryResponse chat = gameClient.getChatHistory(0, 10);
					Map<String, Object> hud = new LinkedHashMap<>();
					hud.put("screen", client.screen());
					hud.put("connectionState", client.connectionState());
					hud.put("recentChat", chat.messages());
					payload = hud;
				}
				default -> throw new IllegalArgumentException("Unknown resource URI: " + uri);
			}
			return Json.MAPPER.valueToTree(payload);
		}, timeoutMs).thenApply(taskResult -> {
			JsonNode json = taskResult.body();
			return new ResourceReadResult(List.of(
					new ResourceContent(uri, "application/json", json.toString())
			));
		});
	}
}
