package dev.advik.mcdt.api.v1;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A feature that a platform (Minecraft version and loader) or the runtime may lack. Routes and MCP tools for a missing
 * capability answer {@code 501 feature-unavailable}. The ids are public API: add new constants, never rename them.
 */
public enum Capability {
	TEXTURES,
	BLOCK_VIEWER,
	WORLD_STREAM,
	SCREENSHOT,
	PACKETS,
	INPUT,
	ADVANCEMENTS,
	RECIPES,
	STATS,
	TOASTS,
	BOSSBARS,
	SUBTITLES,
	OFFHAND,
	ITEM_COMPONENTS,
	CHAT_SIGNING,
	DECOMPILER,
	BARITONE,
	CONFIG_SCREEN;

	/** JSON id, e.g. {@code block-viewer}. */
	@JsonValue
	public String id() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	/** The platform's set narrowed by runtime facts: whether Baritone is installed and the decompiler host is up. */
	public static Set<Capability> effective(Set<Capability> platform, boolean baritoneInstalled, boolean decompilerAvailable) {
		EnumSet<Capability> set = platform.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(platform);
		if (!baritoneInstalled) {
			set.remove(BARITONE);
		}
		if (!decompilerAvailable) {
			set.remove(DECOMPILER);
		}
		return set;
	}

	/** Sorted ids, as reported by {@code /v1/health}. */
	public static List<String> ids(Set<Capability> set) {
		return set.stream().map(Capability::id).sorted().toList();
	}
}
