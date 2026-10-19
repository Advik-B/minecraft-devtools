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
	public static final String HANDLE_NOT_FOUND = "handle-not-found";
	public static final String HANDLE_GONE = "handle-gone";
	public static final String REFLECTION_DENIED = "reflection-denied";
	public static final String CLASS_NOT_FOUND = "class-not-found";
	public static final String FIELD_NOT_FOUND = "field-not-found";
	public static final String PATH_EVALUATION_FAILED = "path-evaluation-failed";
	public static final String BARITONE_NOT_INSTALLED = "baritone-not-installed";
	public static final String MULTIPLAYER_WRITE_DISABLED = "multiplayer-write-disabled";
	public static final String METHOD_NOT_FOUND = "method-not-found";
	public static final String INVOKE_FAILED = "invoke-failed";
	public static final String PACKET_FAILED = "packet-failed";
	public static final String JOB_NOT_FOUND = "job-not-found";

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

	public static ProblemException handleNotFound(String handle) {
		return of(404, HANDLE_NOT_FOUND, "Handle not found",
				"Handle '" + handle + "' does not exist or has expired.");
	}

	public static ProblemException handleGone(String handle) {
		return of(410, HANDLE_GONE, "Object collected",
				"The object referenced by handle '" + handle + "' has been garbage collected.");
	}

	public static ProblemException reflectionDenied(String detail) {
		return of(403, REFLECTION_DENIED, "Reflection denied", detail);
	}

	public static ProblemException classNotFound(String className) {
		return of(404, CLASS_NOT_FOUND, "Class not found",
				"Class '" + className + "' could not be loaded.");
	}

	public static ProblemException fieldNotFound(String fieldName, String className) {
		return of(404, FIELD_NOT_FOUND, "Field not found",
				"Field '" + fieldName + "' was not found on class '" + className + "'.");
	}

	public static ProblemException pathEvaluationFailed(String path, String detail) {
		return of(400, PATH_EVALUATION_FAILED, "Path evaluation failed",
				"Failed evaluating path '" + path + "': " + detail);
	}

	public static ProblemException baritoneNotInstalled() {
		return of(501, BARITONE_NOT_INSTALLED, "Baritone not installed",
				"Baritone 1.13.1 is not installed. Download baritone-api-fabric-1.13.1.jar from https://github.com/cabaletta/baritone/releases and place it in your mods folder.");
	}

	public static ProblemException multiplayerWriteDisabled(String server) {
		return of(403, MULTIPLAYER_WRITE_DISABLED, "Multiplayer write disabled",
				"Write operations are disabled on multiplayer server '" + (server != null ? server : "unknown") + "'. Add it to allowedMultiplayerServers in config/mcdt/config.json to allow automation.");
	}

	public static ProblemException methodNotFound(String methodName, String className) {
		return of(404, METHOD_NOT_FOUND, "Method not found",
				"Method '" + methodName + "' was not found on class '" + className + "' with matching parameter types.");
	}

	public static ProblemException invokeFailed(String methodName, String detail) {
		return of(400, INVOKE_FAILED, "Method invocation failed",
				"Failed to invoke '" + methodName + "': " + detail);
	}

	public static ProblemException packetFailed(String packetName, String detail) {
		return of(400, PACKET_FAILED, "Packet send failed",
				"Failed to build or send packet '" + packetName + "': " + detail);
	}

	public static ProblemException jobNotFound(String jobId) {
		return of(404, JOB_NOT_FOUND, "Job not found",
				"Job '" + jobId + "' does not exist or has expired.");
	}

	public static ProblemException badRequest(String detail) {
		return of(400, BAD_REQUEST, "Bad request", detail);
	}

	public static ProblemException notFound(String detail) {
		return of(404, NOT_FOUND, "Not found", detail);
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
