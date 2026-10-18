package dev.advik.mcdt.http;

import java.util.Map;

/** Factory for every problem type MCDT returns. The {@code type} strings are part of the public API. */
public final class Problems {
	public static final String UNAUTHORIZED = "unauthorized";
	public static final String BAD_HOST = "bad-host";
	public static final String ORIGIN_NOT_ALLOWED = "origin-not-allowed";
	public static final String PERMISSION_DENIED = "permission-denied";
	public static final String RATE_LIMITED = "rate-limited";
	public static final String PAYLOAD_TOO_LARGE = "payload-too-large";
	public static final String QUEUE_FULL = "queue-full";
	public static final String TIMEOUT = "timeout";
	public static final String NOT_FOUND = "not-found";
	public static final String BAD_REQUEST = "bad-request";
	public static final String METHOD_NOT_ALLOWED = "method-not-allowed";
	public static final String SHUTTING_DOWN = "shutting-down";
	public static final String INTERNAL = "internal";
	public static final String NO_PLAYER = "no-player";
	public static final String NO_WORLD = "no-world";
	public static final String BLOCKS_LIMIT_EXCEEDED = "blocks-limit-exceeded";
	public static final String INVALID_COORDINATES = "invalid-coordinates";
	public static final String REGISTRY_NOT_FOUND = "registry-not-found";
	public static final String INVALID_PARAMETER = "invalid-parameter";
	public static final String SCREENSHOT_FAILED = "screenshot-failed";
	public static final String QUIT_TOKEN_INVALID = "quit-token-invalid";
	public static final String QUIT_DISABLED = "quit-disabled";
	public static final String NO_SCREEN = "no-screen";
	public static final String NO_CONTAINER = "no-container";
	public static final String SLOT_OUT_OF_BOUNDS = "slot-out-of-bounds";
	public static final String WIDGET_NOT_FOUND = "widget-not-found";
	public static final String INVALID_ACTION = "invalid-action";

	private Problems() {
	}

	public static ProblemException unauthorized(String detail) {
		return of(401, UNAUTHORIZED, "Unauthorized", detail, Map.of("WWW-Authenticate", "Bearer realm=\"mcdt\""));
	}

	public static ProblemException badHost(String host) {
		return of(403, BAD_HOST, "Host not allowed",
				"The Host header '" + host + "' is not this MCDT instance. Use 127.0.0.1 or localhost.");
	}

	public static ProblemException originNotAllowed(String origin) {
		return of(403, ORIGIN_NOT_ALLOWED, "Origin not allowed",
				"Requests from origin '" + origin + "' are not allowed. Add it to originAllowlist in config/mcdt/config.json.");
	}

	public static ProblemException permissionDenied(String required, String granted) {
		return of(403, PERMISSION_DENIED, "Permission denied",
				"This route needs the '" + required + "' permission; MCDT is configured for '" + granted + "'.");
	}

	public static ProblemException rateLimited() {
		return of(429, RATE_LIMITED, "Too many requests", "Rate limit exceeded; retry shortly.",
				Map.of("Retry-After", "1"));
	}

	public static ProblemException queueFull() {
		return of(503, QUEUE_FULL, "Task queue full",
				"The client thread is busy; retry shortly.", Map.of("Retry-After", "1"));
	}

	public static ProblemException timeout(long timeoutMs) {
		return of(504, TIMEOUT, "Client thread timeout",
				"The game did not run the request within " + timeoutMs + " ms.");
	}

	public static ProblemException shuttingDown() {
		return of(503, SHUTTING_DOWN, "Shutting down", "MCDT is stopping.");
	}

	public static ProblemException internal(String detail) {
		return of(500, INTERNAL, "Internal error", detail);
	}

	public static ProblemException noPlayer() {
		return of(409, NO_PLAYER, "No player loaded", "The client is not currently connected to a world with a player.");
	}

	public static ProblemException noWorld() {
		return of(409, NO_WORLD, "No world loaded", "The client is not currently connected to a world or server.");
	}

	public static ProblemException blocksLimitExceeded(long count, int limit) {
		return of(400, BLOCKS_LIMIT_EXCEEDED, "Blocks limit exceeded",
				"Requested " + count + " blocks, which exceeds the limit of " + limit + " blocks.");
	}

	public static ProblemException invalidCoordinates(String detail) {
		return of(400, INVALID_COORDINATES, "Invalid coordinates", detail);
	}

	public static ProblemException registryNotFound(String registry) {
		return of(404, REGISTRY_NOT_FOUND, "Registry not found", "Registry '" + registry + "' does not exist.");
	}

	public static ProblemException invalidParameter(String name, String detail) {
		return of(400, INVALID_PARAMETER, "Invalid parameter: " + name, detail);
	}

	public static ProblemException screenshotFailed(String detail) {
		return of(500, SCREENSHOT_FAILED, "Screenshot failed", detail);
	}

	public static ProblemException quitTokenInvalid() {
		return of(409, QUIT_TOKEN_INVALID, "Invalid confirmation token",
				"The confirmation token is invalid, expired, or was already used. Request a new token from step 1.");
	}

	public static ProblemException quitDisabled() {
		return of(403, QUIT_DISABLED, "Quit disabled",
				"Quitting the game via MCDT is disabled in the server configuration (allowQuit is false).");
	}

	public static ProblemException noScreen() {
		return of(409, NO_SCREEN, "No screen open", "The client does not currently have any GUI screen open.");
	}

	public static ProblemException noContainer() {
		return of(409, NO_CONTAINER, "No container open", "The current screen is not a container screen with interactive slots.");
	}

	public static ProblemException slotOutOfBounds(int slot, int totalSlots) {
		return of(400, SLOT_OUT_OF_BOUNDS, "Slot out of bounds",
				"Slot index " + slot + " is out of bounds for the current container (size: " + totalSlots + ").");
	}

	public static ProblemException widgetNotFound(int index) {
		return of(404, WIDGET_NOT_FOUND, "Widget not found",
				"No widget found at index " + index + " in the current screen.");
	}

	public static ProblemException invalidAction(String action, String detail) {
		return of(400, INVALID_ACTION, "Invalid action: " + action, detail);
	}

	public static ProblemException of(int status, String type, String title, String detail) {
		return of(status, type, title, detail, Map.of());
	}

	public static ProblemException of(int status, String type, String title, String detail, Map<String, String> headers) {
		return new ProblemException(new Problem(type, title, status, detail, null), headers);
	}

	/** Maps a bare HTTP status (from Javalin or Jetty) to a problem type. */
	public static Problem forStatus(int status, String detail) {
		return switch (status) {
			case 400 -> new Problem(BAD_REQUEST, "Bad request", status, detail, null);
			case 401 -> new Problem(UNAUTHORIZED, "Unauthorized", status, detail, null);
			case 404 -> new Problem(NOT_FOUND, "Not found", status, detail, null);
			case 405 -> new Problem(METHOD_NOT_ALLOWED, "Method not allowed", status, detail, null);
			case 413 -> new Problem(PAYLOAD_TOO_LARGE, "Payload too large", status, detail, null);
			default -> new Problem(status >= 500 ? INTERNAL : BAD_REQUEST, "HTTP " + status, status, detail, null);
		};
	}
}
