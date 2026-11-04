package dev.advik.mcdt.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigUpdaterTest {

	@Test
	void applyLiveCopiesLiveFieldsOnly() {
		McdtConfig live = new McdtConfig();
		McdtConfig draft = new McdtConfig();
		draft.permission = Permission.REFLECT_WRITE;
		draft.allowQuit = false;
		draft.requestTimeoutMs = 9_000;
		draft.tickBudgetMicros = 4_000;
		draft.enableMcp = false;
		draft.mcpLogCalls = false;
		draft.enableBaritone = false;
		draft.eventSubscriberQueueCapacity = 512;
		draft.originAllowlist = new ArrayList<>(List.of("http://localhost:5173"));
		draft.allowedMultiplayerServers = new ArrayList<>(List.of("play.example.com"));
		draft.enableViewBlocks = false;
		draft.maxViewBlocksRays = 50_000;
		// startup-only values must not leak into the running config
		draft.preferredPort = 31_000;
		draft.bindAddress = "0.0.0.0";
		draft.queueCapacity = 1;

		ConfigUpdater.applyLive(live, draft);

		assertEquals(Permission.REFLECT_WRITE, live.permission);
		assertFalse(live.allowQuit);
		assertEquals(9_000, live.requestTimeoutMs);
		assertEquals(4_000, live.tickBudgetMicros);
		assertFalse(live.enableMcp);
		assertFalse(live.mcpLogCalls);
		assertFalse(live.enableBaritone);
		assertEquals(512, live.eventSubscriberQueueCapacity);
		assertEquals(List.of("http://localhost:5173"), live.originAllowlist);
		assertEquals(List.of("play.example.com"), live.allowedMultiplayerServers);
		assertFalse(live.enableViewBlocks);
		assertEquals(50_000, live.maxViewBlocksRays);
		assertEquals(McdtConfig.DEFAULT_PORT, live.preferredPort);
		assertEquals("127.0.0.1", live.bindAddress);
		assertEquals(256, live.queueCapacity);
	}

	@Test
	void applyLiveCopiesListsInsteadOfAliasingThem() {
		McdtConfig live = new McdtConfig();
		McdtConfig draft = new McdtConfig();
		draft.originAllowlist = new ArrayList<>(List.of("http://a"));

		ConfigUpdater.applyLive(live, draft);
		draft.originAllowlist.add("http://b");

		assertEquals(List.of("http://a"), live.originAllowlist);
	}

	@Test
	void needsRestartIsFalseForLiveOnlyChanges() {
		McdtConfig live = new McdtConfig();
		McdtConfig draft = new McdtConfig();
		draft.permission = Permission.ACT;
		draft.requestTimeoutMs = 10_000;
		draft.enableBaritone = false;

		assertFalse(ConfigUpdater.needsRestart(live, draft));
	}

	@Test
	void needsRestartIsTrueForEachStartupOnlyChange() {
		assertTrue(restartAfter(d -> d.enabled = false));
		assertTrue(restartAfter(d -> d.preferredPort = 1234));
		assertTrue(restartAfter(d -> d.bindAddress = "0.0.0.0"));
		assertTrue(restartAfter(d -> d.queueCapacity = 10));
		assertTrue(restartAfter(d -> d.rateLimitPerSecond = 10));
		assertTrue(restartAfter(d -> d.maxRequestBytes = 2_048));
		assertTrue(restartAfter(d -> d.eventRingBufferSize = 128));
		assertTrue(restartAfter(d -> d.mcpNotificationThrottleMs = 999));
	}

	private static boolean restartAfter(java.util.function.Consumer<McdtConfig> change) {
		McdtConfig draft = new McdtConfig();
		change.accept(draft);
		return ConfigUpdater.needsRestart(new McdtConfig(), draft);
	}
}
