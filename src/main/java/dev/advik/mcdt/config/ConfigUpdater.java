package dev.advik.mcdt.config;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Applies edited settings to a running {@link McdtConfig}. Only settings that are read live are copied; settings
 * read once at startup are left alone because other code (for example host matching against the bound socket)
 * would otherwise see an inconsistent mix of old and new values.
 */
public final class ConfigUpdater {
	private ConfigUpdater() {
	}

	/** Copies the settings that are read live from {@code draft} into {@code live}. */
	public static void applyLive(McdtConfig live, McdtConfig draft) {
		live.permission = draft.permission;
		live.allowQuit = draft.allowQuit;
		live.requestTimeoutMs = draft.requestTimeoutMs;
		live.tickBudgetMicros = draft.tickBudgetMicros;
		live.enableMcp = draft.enableMcp;
		live.mcpLogCalls = draft.mcpLogCalls;
		live.enableBaritone = draft.enableBaritone;
		live.eventSubscriberQueueCapacity = draft.eventSubscriberQueueCapacity;
		live.enableViewBlocks = draft.enableViewBlocks;
		live.maxViewBlocksRays = draft.maxViewBlocksRays;
		live.originAllowlist = new ArrayList<>(draft.originAllowlist);
		live.allowedHosts = new ArrayList<>(draft.allowedHosts);
		live.allowedMultiplayerServers = new ArrayList<>(draft.allowedMultiplayerServers);
	}

	/** Whether {@code draft} differs from {@code live} in any setting that is only read at startup. */
	public static boolean needsRestart(McdtConfig live, McdtConfig draft) {
		return live.enabled != draft.enabled
				|| live.preferredPort != draft.preferredPort
				|| !Objects.equals(live.bindAddress, draft.bindAddress)
				|| live.queueCapacity != draft.queueCapacity
				|| live.rateLimitPerSecond != draft.rateLimitPerSecond
				|| live.maxRequestBytes != draft.maxRequestBytes
				|| live.eventRingBufferSize != draft.eventRingBufferSize
				|| live.mcpNotificationThrottleMs != draft.mcpNotificationThrottleMs;
	}
}
