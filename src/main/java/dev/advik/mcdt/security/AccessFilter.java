package dev.advik.mcdt.security;

import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.config.ServerAllowlist;
import dev.advik.mcdt.http.Problems;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.security.RouteRole;

import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Enforces Host, Origin, Bearer authentication, rate limiting, request size limits, and route permissions.
 */
public final class AccessFilter implements Handler {
	private final McdtConfig config;
	private final BearerToken token;
	private final RateLimiter rateLimiter;
	private final IntSupplier portSupplier;
	private final dev.advik.mcdt.game.GameClient gameClient;

	public AccessFilter(McdtConfig config, BearerToken token, RateLimiter rateLimiter, IntSupplier portSupplier) {
		this(config, token, rateLimiter, portSupplier, null);
	}

	public AccessFilter(McdtConfig config, BearerToken token, RateLimiter rateLimiter, IntSupplier portSupplier, dev.advik.mcdt.game.GameClient gameClient) {
		this.config = config;
		this.token = token;
		this.rateLimiter = rateLimiter;
		this.portSupplier = portSupplier;
		this.gameClient = gameClient;
	}


	@Override
	public void handle(Context ctx) {
		checkHost(ctx);
		checkOrigin(ctx);

		String path = ctx.path();

		// Docs, OpenAPI spec, and root endpoints are public and do not require authentication or rate limits.
		if (!path.startsWith("/v1") && !path.startsWith("/mcp")) {
			return;
		}

		// Security: query-string tokens are forbidden to prevent leakage in URLs/logs.
		if (ctx.queryParam("token") != null || ctx.queryParam("bearer") != null) {
			throw Problems.unauthorized("Tokens must be sent in the Authorization header, not in query parameters");
		}

		checkBearer(ctx);
		checkPayloadSize(ctx);
	}

	private void checkHost(Context ctx) {
		String hostHeader = ctx.header("Host");
		if (hostHeader == null || hostHeader.isBlank()) {
			throw Problems.badHost("missing");
		}
		if (!isHostAllowed(config, hostHeader)) {
			throw Problems.badHost(hostHeader);
		}
	}

	/** Whether a Host header names loopback, the bind address, or an entry in {@code allowedHosts}. */
	public static boolean isHostAllowed(McdtConfig config, String hostHeader) {
		// Extract hostname without port (handles "127.0.0.1:25580", "[::1]:25580", "localhost")
		String hostName = hostHeader.trim();
		if (hostName.startsWith("[")) {
			int closeBracket = hostName.indexOf(']');
			if (closeBracket != -1) {
				hostName = hostName.substring(1, closeBracket);
			}
		} else {
			int colon = hostName.indexOf(':');
			if (colon != -1) {
				hostName = hostName.substring(0, colon);
			}
		}

		boolean isLoopback = "127.0.0.1".equalsIgnoreCase(hostName) || "localhost".equalsIgnoreCase(hostName)
				|| "::1".equalsIgnoreCase(hostName);
		boolean matchesBind = config.bindAddress != null && config.bindAddress.equalsIgnoreCase(hostName);
		return isLoopback || matchesBind || config.allowedHosts.stream().anyMatch(hostName::equalsIgnoreCase);
	}

	private void checkOrigin(Context ctx) {
		String origin = ctx.header("Origin");
		if (origin == null || origin.isBlank()) {
			return;
		}

		if (!isOriginAllowed(config, portSupplier.getAsInt(), origin)) {
			throw Problems.originNotAllowed(origin);
		}

		// If allowed, echo back CORS headers for browser tooling
		ctx.header("Access-Control-Allow-Origin", origin);
		ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
		ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type, Origin, Accept");
	}

	/** Whether a browser Origin is the API itself (on loopback) or an entry in {@code originAllowlist}. */
	public static boolean isOriginAllowed(McdtConfig config, int port, String origin) {
		boolean isSelf = origin.equalsIgnoreCase("http://127.0.0.1:" + port)
				|| origin.equalsIgnoreCase("http://localhost:" + port);
		return isSelf || config.originAllowlist.stream().anyMatch(origin::equalsIgnoreCase);
	}

	private void checkBearer(Context ctx) {
		String auth = ctx.header("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			throw Problems.unauthorized("Missing or malformed Authorization header (expected 'Bearer <token>')");
		}

		String presented = auth.substring("Bearer ".length()).trim();
		if (!token.matches(presented)) {
			throw Problems.unauthorized("Invalid Bearer token");
		}

		if (!rateLimiter.tryAcquire(presented)) {
			throw Problems.rateLimited();
		}
	}

	private void checkPayloadSize(Context ctx) {
		long length = ctx.contentLength();
		if (length > config.maxRequestBytes) {
			throw Problems.of(413, Problems.PAYLOAD_TOO_LARGE, "Payload too large",
					"Request body length " + length + " exceeds maximum allowed (" + config.maxRequestBytes + " bytes)");
		}
	}

	public void checkPermission(Context ctx) {
		Set<RouteRole> routeRoles = ctx.routeRoles();
		if (routeRoles == null || routeRoles.isEmpty()) {
			return;
		}

		for (RouteRole role : routeRoles) {
			if (role instanceof McdtRole mcdtRole) {
				Permission required = mcdtRole.permission();
				if (!config.permission.grants(required)) {
					throw Problems.permissionDenied(required.id(), config.permission.id());
				}
				if (gameClient != null && (required == Permission.ACT || required == Permission.REFLECT_WRITE)) {
					if (gameClient.isMultiplayer()) {
						String serverAddress = gameClient.getCurrentServerAddress();
						if (!ServerAllowlist.allows(config.allowedMultiplayerServers, serverAddress)) {
							throw Problems.multiplayerWriteDisabled(serverAddress);
						}
					}
				}
			}
		}
	}
}

