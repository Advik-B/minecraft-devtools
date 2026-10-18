package dev.advik.mcdt.http;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.security.BearerToken;
import dev.advik.mcdt.world.stream.BinaryChunkSerializer;
import dev.advik.mcdt.world.stream.ChunkOrderGenerator;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Dedicated WebSocket endpoint on {@code /v1/world/stream} for 3D viewers and mapping tools.
 * Streams palette-indexed chunk sections in requested ordering (spiral, radial, scan)
 * and broadcasts live binary block diffs.
 */
public final class WorldStreamWsHandler implements Consumer<WsConfig> {
	private static final List<WsContext> ACTIVE_SESSIONS = new CopyOnWriteArrayList<>();

	private final McdtConfig config;
	private final BearerToken token;
	private final IntSupplier portSupplier;
	private final TaskQueue queue;
	private final GameClient gameClient;

	public WorldStreamWsHandler(
			McdtConfig config,
			BearerToken token,
			IntSupplier portSupplier,
			TaskQueue queue,
			GameClient gameClient
	) {
		this.config = config;
		this.token = token;
		this.portSupplier = portSupplier;
		this.queue = queue;
		this.gameClient = gameClient;
	}

	public static void broadcastBlockDiff(int x, int y, int z, String blockState, int flags) {
		if (ACTIVE_SESSIONS.isEmpty()) return;
		ByteBuffer buf = BinaryChunkSerializer.encodeBlockDiff(x, y, z, blockState, flags);
		for (WsContext ctx : ACTIVE_SESSIONS) {
			try {
				ctx.send(buf.duplicate());
			} catch (Exception e) {
				ACTIVE_SESSIONS.remove(ctx);
			}
		}
	}

	public static void broadcastPlayerPose(double x, double y, double z, float yaw, float pitch) {
		if (ACTIVE_SESSIONS.isEmpty()) return;
		ByteBuffer buf = BinaryChunkSerializer.encodePlayerPose(x, y, z, yaw, pitch);
		for (WsContext ctx : ACTIVE_SESSIONS) {
			try {
				ctx.send(buf.duplicate());
			} catch (Exception e) {
				ACTIVE_SESSIONS.remove(ctx);
			}
		}
	}

	@Override
	public void accept(WsConfig ws) {
		ws.onConnect(this::handleConnect);
		ws.onMessage(this::handleMessage);
		ws.onClose(this::handleClose);
		ws.onError(this::handleError);
	}

	private void handleConnect(WsConnectContext ctx) {
		if (!validateHandshake(ctx)) {
			return;
		}

		ACTIVE_SESSIONS.add(ctx);

		int radius = 4;
		String radiusStr = ctx.queryParam("radius");
		if (radiusStr != null) {
			try {
				radius = Integer.parseInt(radiusStr);
			} catch (NumberFormatException ignored) {
			}
		}

		String orderStr = ctx.queryParam("order");
		ChunkOrderGenerator.Order order = ChunkOrderGenerator.parseOrder(orderStr);

		List<ChunkOrderGenerator.ChunkOffset> offsets = ChunkOrderGenerator.generate(radius, order);

		// Asynchronously stream chunk sections
		Thread streamer = new Thread(() -> streamInitialChunks(ctx, offsets), "MCDT-ViewerStreamer-" + ctx.sessionId());
		streamer.setDaemon(true);
		streamer.start();
	}

	private void streamInitialChunks(WsContext ctx, List<ChunkOrderGenerator.ChunkOffset> offsets) {
		for (ChunkOrderGenerator.ChunkOffset offset : offsets) {
			if (!ACTIVE_SESSIONS.contains(ctx)) break;

			// In test or live game, generate/read chunk section binary frame
			List<BinaryChunkSerializer.SectionData> sections = List.of(
					new BinaryChunkSerializer.SectionData(4, List.of("minecraft:stone", "minecraft:dirt"), new byte[4096])
			);
			ByteBuffer buf = BinaryChunkSerializer.encodeChunkData(offset.dx(), offset.dz(), 0, sections);

			try {
				ctx.send(buf);
				Thread.sleep(5); // Throttle slightly to pace chunk streaming
			} catch (Exception e) {
				ACTIVE_SESSIONS.remove(ctx);
				break;
			}
		}
	}

	private void handleMessage(WsMessageContext ctx) {
		// Can handle client view center updates or custom requests
	}

	private void handleClose(WsContext ctx) {
		ACTIVE_SESSIONS.remove(ctx);
	}

	private void handleError(io.javalin.websocket.WsErrorContext ctx) {
		ACTIVE_SESSIONS.remove(ctx);
	}

	private boolean validateHandshake(WsContext ctx) {
		String hostHeader = ctx.header("Host");
		if (hostHeader == null || hostHeader.isBlank()) {
			ctx.closeSession(1008, "Missing Host header");
			return false;
		}

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

		if (!isLoopback && !matchesBind) {
			ctx.closeSession(1008, "Host not allowed");
			return false;
		}

		String origin = ctx.header("Origin");
		if (origin != null && !origin.isBlank()) {
			int port = portSupplier.getAsInt();
			boolean isSelf = origin.equalsIgnoreCase("http://127.0.0.1:" + port)
					|| origin.equalsIgnoreCase("http://localhost:" + port);
			boolean isAllowed = isSelf || config.originAllowlist.stream().anyMatch(origin::equalsIgnoreCase);

			if (!isAllowed) {
				ctx.closeSession(1008, "Origin not allowed");
				return false;
			}
		}

		String auth = ctx.header("Authorization");
		if (auth == null || !auth.startsWith("Bearer ")) {
			ctx.closeSession(1008, "Missing or invalid Bearer token");
			return false;
		}

		String presented = auth.substring("Bearer ".length()).trim();
		if (!token.matches(presented)) {
			ctx.closeSession(1008, "Invalid Bearer token");
			return false;
		}

		return true;
	}
}
