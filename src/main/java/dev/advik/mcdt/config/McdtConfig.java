package dev.advik.mcdt.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * User settings, stored as {@code config/mcdt/config.json}. Missing fields keep their defaults, so older files
 * upgrade in place.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class McdtConfig {
	public static final int DEFAULT_PORT = 25580;

	/** Start the HTTP server at all. */
	public boolean enabled = true;
	/** Preferred port; a random free port is used if it is taken. {@code MCDT_PORT} overrides it. */
	public int preferredPort = DEFAULT_PORT;
	/** Address to bind. Anything other than loopback exposes the API to the network. */
	public String bindAddress = "127.0.0.1";
	/** How long a request waits for the client thread before {@code 504}. */
	public volatile long requestTimeoutMs = 5_000;
	/** Time the client thread may spend on queued tasks per tick. */
	public volatile long tickBudgetMicros = 2_000;
	/** Maximum queued tasks before {@code 503}. */
	public int queueCapacity = 256;
	/** Highest permission level granted to API clients. */
	public volatile Permission permission = Permission.READ;
	/** Extra browser origins allowed to call the API, e.g. {@code http://localhost:5173}. */
	public volatile List<String> originAllowlist = new ArrayList<>();
	/** Extra Host header names accepted besides loopback and the bind address, e.g. a reverse proxy's domain. */
	public volatile List<String> allowedHosts = new ArrayList<>();
	/** Allowed multiplayer server addresses for act and reflect-write actions. */
	public volatile List<String> allowedMultiplayerServers = new ArrayList<>();
	/** Enable or disable Baritone endpoints. */
	public volatile boolean enableBaritone = true;
	/** Whether the two-step quit endpoints may shut the game down. */
	public volatile boolean allowQuit = true;
	/** Requests per second per token. */
	public int rateLimitPerSecond = 200;
	/** Largest accepted request body. */
	public long maxRequestBytes = 1_048_576;
	/** Capacity of the lock-free event ring buffer. */
	public int eventRingBufferSize = 4_096;
	/** Capacity of each WebSocket subscriber's outgoing event queue. */
	public volatile int eventSubscriberQueueCapacity = 256;
	/** Enable or disable the Model Context Protocol (MCP) server. */
	public volatile boolean enableMcp = true;
	/** Whether to log recent MCP tool calls in an in-memory ring buffer. */
	public volatile boolean mcpLogCalls = true;
	/** Minimum milliseconds between resource update notifications per URI per session. */
	public long mcpNotificationThrottleMs = 250;
	/** Enable or disable the /v1/view/blocks endpoint. */
	public volatile boolean enableViewBlocks = true;
	/** Maximum resolution (width * height rays) allowed for /v1/view/blocks (default 512x288 = 147456). */
	public volatile int maxViewBlocksRays = 147_456;

	/** Clamps out-of-range values to safe ones. Returns {@code this}. */
	public McdtConfig normalize() {
		if (preferredPort < 0 || preferredPort > 65_535) {
			preferredPort = DEFAULT_PORT;
		}
		if (bindAddress == null || bindAddress.isBlank()) {
			bindAddress = "127.0.0.1";
		}
		requestTimeoutMs = clamp(requestTimeoutMs, 100, 120_000);
		tickBudgetMicros = clamp(tickBudgetMicros, 100, 20_000);
		queueCapacity = (int) clamp(queueCapacity, 1, 65_536);
		rateLimitPerSecond = (int) clamp(rateLimitPerSecond, 1, 100_000);
		maxRequestBytes = clamp(maxRequestBytes, 1_024, 64L * 1_048_576);
		eventRingBufferSize = (int) clamp(eventRingBufferSize, 64, 65_536);
		eventSubscriberQueueCapacity = (int) clamp(eventSubscriberQueueCapacity, 16, 4_096);
		mcpNotificationThrottleMs = clamp(mcpNotificationThrottleMs, 50, 10_000);
		maxViewBlocksRays = (int) clamp(maxViewBlocksRays, 100, 1_000_000);
		if (permission == null) {
			permission = Permission.READ;
		}
		if (originAllowlist == null) {
			originAllowlist = new ArrayList<>();
		}
		originAllowlist.removeIf(origin -> origin == null || origin.isBlank());
		if (allowedHosts == null) {
			allowedHosts = new ArrayList<>();
		}
		allowedHosts.removeIf(host -> host == null || host.isBlank());
		if (allowedMultiplayerServers == null) {
			allowedMultiplayerServers = new ArrayList<>();
		}
		allowedMultiplayerServers.removeIf(s -> s == null || s.isBlank());
		return this;
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}
}
