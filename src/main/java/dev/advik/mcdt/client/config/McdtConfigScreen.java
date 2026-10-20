package dev.advik.mcdt.client.config;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.McdtCore;
import dev.advik.mcdt.client.MinecraftDevToolsClient;
import dev.advik.mcdt.config.ConfigStore;
import dev.advik.mcdt.config.ConfigUpdater;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionFlag;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.ControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.LongFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builds the in-game MCDT settings screen with YACL.
 *
 * <p>The screen edits a draft read from {@code config.json}. On Done the draft is written to disk and only the
 * settings that are read live are copied into the running config (see {@link ConfigUpdater}). Settings that are
 * read once at startup are flagged {@link OptionFlag#GAME_RESTART}.
 */
public final class McdtConfigScreen {
	private static final String KEY = "mcdt.config.";
	private static final McdtConfig DEFAULTS = new McdtConfig();

	private final McdtConfig draft;
	private final McdtConfig live;
	private final ConfigStore store;

	private McdtConfigScreen(ConfigStore store, McdtConfig live) {
		this.store = store;
		this.live = live;
		this.draft = store.load();
	}

	public static Screen create(Screen parent) {
		McdtCore core = MinecraftDevToolsClient.core().orElse(null);
		McdtConfigScreen builder;
		if (core != null) {
			builder = new McdtConfigScreen(core.configStore(), core.config());
		} else {
			// The core failed to start, but the file can still be edited.
			ConfigStore store = new ConfigStore(
					FabricLoader.getInstance().getConfigDir().resolve(Mcdt.MOD_ID).resolve("config.json"));
			builder = new McdtConfigScreen(store, new McdtConfig());
		}
		return builder.build().generateScreen(parent);
	}

	private YetAnotherConfigLib build() {
		return YetAnotherConfigLib.createBuilder()
				.title(Component.translatable(KEY + "title"))
				.category(serverCategory())
				.category(securityCategory())
				.category(performanceCategory())
				.category(integrationsCategory())
				.save(this::save)
				.build();
	}

	private void save() {
		draft.normalize();
		ConfigUpdater.applyLive(live, draft);
		store.save(draft);
		Mcdt.LOGGER.info("MCDT config saved to {}", store.file());
	}

	// ---- categories ----

	private ConfigCategory serverCategory() {
		return ConfigCategory.createBuilder()
				.name(Component.translatable(KEY + "category.server"))
				.tooltip(Component.translatable(KEY + "category.server.desc"))
				.option(restartOption("enabled", DEFAULTS.enabled, () -> draft.enabled, v -> draft.enabled = v,
						TickBoxControllerBuilder::create))
				.option(restartOption("preferredPort", DEFAULTS.preferredPort,
						() -> draft.preferredPort, v -> draft.preferredPort = v,
						o -> IntegerFieldControllerBuilder.create(o).range(0, 65_535)))
				.option(restartOption("bindAddress", DEFAULTS.bindAddress,
						() -> draft.bindAddress, v -> draft.bindAddress = v,
						StringControllerBuilder::create, Component.translatable(KEY + "bindAddress.warning")))
				.build();
	}

	private ConfigCategory securityCategory() {
		return ConfigCategory.createBuilder()
				.name(Component.translatable(KEY + "category.security"))
				.tooltip(Component.translatable(KEY + "category.security.desc"))
				.option(liveOption("permission", DEFAULTS.permission,
						() -> draft.permission, v -> draft.permission = v,
						o -> EnumControllerBuilder.create(o).enumClass(Permission.class)
								.formatValue(p -> Component.translatable(KEY + "permission." + p.id())),
						Component.translatable(KEY + "permission.warning")))
				.option(liveOption("allowQuit", DEFAULTS.allowQuit, () -> draft.allowQuit, v -> draft.allowQuit = v,
						TickBoxControllerBuilder::create))
				.option(restartOption("rateLimitPerSecond", DEFAULTS.rateLimitPerSecond,
						() -> draft.rateLimitPerSecond, v -> draft.rateLimitPerSecond = v,
						o -> IntegerFieldControllerBuilder.create(o).range(1, 100_000)))
				.option(restartOption("maxRequestBytes", DEFAULTS.maxRequestBytes,
						() -> draft.maxRequestBytes, v -> draft.maxRequestBytes = v,
						o -> LongFieldControllerBuilder.create(o).range(1_024L, 64L * 1_048_576)))
				.group(stringList("originAllowlist", () -> draft.originAllowlist, v -> draft.originAllowlist = v))
				.group(stringList("allowedHosts", () -> draft.allowedHosts, v -> draft.allowedHosts = v))
				.group(stringList("allowedMultiplayerServers",
						() -> draft.allowedMultiplayerServers, v -> draft.allowedMultiplayerServers = v))
				.build();
	}

	private ConfigCategory performanceCategory() {
		return ConfigCategory.createBuilder()
				.name(Component.translatable(KEY + "category.performance"))
				.tooltip(Component.translatable(KEY + "category.performance.desc"))
				.option(liveOption("requestTimeoutMs", DEFAULTS.requestTimeoutMs,
						() -> draft.requestTimeoutMs, v -> draft.requestTimeoutMs = v,
						o -> LongFieldControllerBuilder.create(o).range(100L, 120_000L)))
				.option(liveOption("tickBudgetMicros", DEFAULTS.tickBudgetMicros,
						() -> draft.tickBudgetMicros, v -> draft.tickBudgetMicros = v,
						o -> LongFieldControllerBuilder.create(o).range(100L, 20_000L)))
				.option(liveOption("eventSubscriberQueueCapacity", DEFAULTS.eventSubscriberQueueCapacity,
						() -> draft.eventSubscriberQueueCapacity, v -> draft.eventSubscriberQueueCapacity = v,
						o -> IntegerFieldControllerBuilder.create(o).range(16, 4_096)))
				.option(restartOption("queueCapacity", DEFAULTS.queueCapacity,
						() -> draft.queueCapacity, v -> draft.queueCapacity = v,
						o -> IntegerFieldControllerBuilder.create(o).range(1, 65_536)))
				.option(restartOption("eventRingBufferSize", DEFAULTS.eventRingBufferSize,
						() -> draft.eventRingBufferSize, v -> draft.eventRingBufferSize = v,
						o -> IntegerFieldControllerBuilder.create(o).range(64, 65_536)))
				.build();
	}

	private ConfigCategory integrationsCategory() {
		return ConfigCategory.createBuilder()
				.name(Component.translatable(KEY + "category.integrations"))
				.tooltip(Component.translatable(KEY + "category.integrations.desc"))
				.option(liveOption("enableMcp", DEFAULTS.enableMcp, () -> draft.enableMcp, v -> draft.enableMcp = v,
						TickBoxControllerBuilder::create))
				.option(liveOption("mcpLogCalls", DEFAULTS.mcpLogCalls,
						() -> draft.mcpLogCalls, v -> draft.mcpLogCalls = v,
						TickBoxControllerBuilder::create))
				.option(liveOption("enableBaritone", DEFAULTS.enableBaritone,
						() -> draft.enableBaritone, v -> draft.enableBaritone = v,
						TickBoxControllerBuilder::create))
				.option(restartOption("mcpNotificationThrottleMs", DEFAULTS.mcpNotificationThrottleMs,
						() -> draft.mcpNotificationThrottleMs, v -> draft.mcpNotificationThrottleMs = v,
						o -> LongFieldControllerBuilder.create(o).range(50L, 10_000L)))
				.build();
	}

	// ---- option helpers ----

	private static <T> Option<T> liveOption(String key, T def, Supplier<T> getter, Consumer<T> setter,
			Function<Option<T>, ControllerBuilder<T>> controller, Component... extra) {
		return option(key, def, getter, setter, controller, false, extra);
	}

	private static <T> Option<T> restartOption(String key, T def, Supplier<T> getter, Consumer<T> setter,
			Function<Option<T>, ControllerBuilder<T>> controller, Component... extra) {
		return option(key, def, getter, setter, controller, true, extra);
	}

	private static <T> Option<T> option(String key, T def, Supplier<T> getter, Consumer<T> setter,
			Function<Option<T>, ControllerBuilder<T>> controller, boolean restart, Component... extra) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable(KEY + key + ".desc"));
		for (Component line : extra) {
			lines.add(CommonComponents.EMPTY);
			lines.add(line);
		}
		lines.add(CommonComponents.EMPTY);
		lines.add(Component.translatable(KEY + (restart ? "restart_required" : "applies_live")));

		Option.Builder<T> builder = Option.<T>createBuilder()
				.name(Component.translatable(KEY + key))
				.description(OptionDescription.of(lines.toArray(new Component[0])))
				.binding(def, getter, setter)
				.controller(controller);
		if (restart) {
			builder.flag(OptionFlag.GAME_RESTART);
		}
		return builder.build();
	}

	private static ListOption<String> stringList(String key, Supplier<List<String>> getter,
			Consumer<List<String>> setter) {
		return ListOption.<String>createBuilder()
				.name(Component.translatable(KEY + key))
				.description(OptionDescription.of(
						Component.translatable(KEY + key + ".desc"),
						CommonComponents.EMPTY,
						Component.translatable(KEY + "applies_live")))
				.binding(new ArrayList<>(), () -> new ArrayList<>(getter.get()), setter)
				.controller(StringControllerBuilder::create)
				.initial("")
				.collapsed(false)
				.build();
	}
}
