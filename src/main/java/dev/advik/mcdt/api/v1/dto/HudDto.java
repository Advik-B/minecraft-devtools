package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.javalin.openapi.OpenApiName;

import java.util.List;

/**
 * DTO for the HUD telemetry endpoint /v1/hud.
 */
public final class HudDto {
	private HudDto() {}

	@OpenApiName("HudTitleInfo")
	public record TitleInfo(
			String title,
			String subtitle,
			int fadeInTicks,
			int stayTicks,
			int fadeOutTicks
	) {}

	@OpenApiName("HudBossBarInfo")
	public record BossBarInfo(
			String uuid,
			String name,
			float percent,
			String color,
			String overlay,
			boolean darkenScreen,
			boolean playMusic,
			boolean createWorldFog
	) {}

	@OpenApiName("HudScoreboardSidebar")
	public record ScoreboardSidebar(
			String objective,
			String title,
			List<ScoreEntry> scores
	) {
		@OpenApiName("HudScoreEntry")
		public record ScoreEntry(String owner, int score, String display) {}
	}

	@OpenApiName("HudTabList")
	public record TabList(
			String header,
			String footer,
			List<PlayerEntry> players
	) {
		@OpenApiName("HudPlayerEntry")
		public record PlayerEntry(String name, String uuid, int latency, String gameMode, String displayName) {}
	}

	@OpenApiName("HudSubtitleEntry")
	public record SubtitleEntry(
			String text,
			double volume,
			double[] location
	) {}

	@OpenApiName("HudToastInfo")
	public record ToastInfo(
			String type,
			String title,
			String description
	) {}

	@OpenApiName("HudHeldItemInfo")
	public record HeldItemInfo(
			InventoryDto.SlotInfo mainhand,
			InventoryDto.SlotInfo offhand,
			int selectedHotbarSlot
	) {}

	@OpenApiName("HudResponse")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Response(
			long tick,
			String actionBar,
			TitleInfo title,
			List<BossBarInfo> bossBars,
			ScoreboardSidebar scoreboard,
			TabList tabList,
			List<SubtitleEntry> subtitles,
			HeldItemInfo heldItem,
			List<ToastInfo> toasts
	) {}
}
