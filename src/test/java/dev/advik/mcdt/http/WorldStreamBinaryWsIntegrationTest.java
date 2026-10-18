package dev.advik.mcdt.http;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.security.BearerToken;
import dev.advik.mcdt.world.stream.BinaryChunkSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorldStreamBinaryWsIntegrationTest {
	@TempDir
	Path tempDir;

	private McdtEnvironment env;
	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private MockGameClient gameClient;
	private HttpServer server;
	private HttpClient httpClient;

	@BeforeEach
	void setUp() {
		EventHub.resetForTesting();

		env = new McdtEnvironment(
				"0.1.0-SNAPSHOT",
				"1.21.4",
				tempDir,
				tempDir.resolve("config"),
				tempDir.resolve("logs"),
				tempDir.resolve("home"),
				"TestPlayer",
				"not-installed",
				Instant.now()
		);

		config = new McdtConfig();
		config.preferredPort = 0;
		config.permission = Permission.READ;

		token = BearerToken.of("test-viewer-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		gameClient = new MockGameClient();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), gameClient);
		httpClient = HttpClient.newHttpClient();
	}

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
		}
		EventHub.resetForTesting();
	}

	@Test
	void streamsBinaryChunkDataAndBlockDiffs() throws Exception {
		List<ByteBuffer> binaryPackets = new CopyOnWriteArrayList<>();
		CountDownLatch chunkLatch = new CountDownLatch(1);
		CountDownLatch diffLatch = new CountDownLatch(1);

		WebSocket ws = httpClient.newWebSocketBuilder()
				.header("Authorization", "Bearer test-viewer-token")
				.header("Host", "127.0.0.1:" + server.port())
				.buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/v1/world/stream?radius=1&order=spiral"), new WebSocket.Listener() {
					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(100);
					}

					@Override
					public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
						ByteBuffer copy = ByteBuffer.allocate(data.remaining()).order(ByteOrder.BIG_ENDIAN);
						copy.put(data);
						copy.flip();
						binaryPackets.add(copy);

						byte packetType = copy.get(0);
						if (packetType == BinaryChunkSerializer.PACKET_CHUNK_DATA) {
							chunkLatch.countDown();
						} else if (packetType == BinaryChunkSerializer.PACKET_BLOCK_DIFF) {
							diffLatch.countDown();
						}

						webSocket.request(1);
						return CompletableFuture.completedFuture(null);
					}
				})
				.get(5, TimeUnit.SECONDS);

		assertTrue(chunkLatch.await(5, TimeUnit.SECONDS));

		// Verify chunk packet header
		ByteBuffer chunkPacket = binaryPackets.stream()
				.filter(b -> b.get(0) == BinaryChunkSerializer.PACKET_CHUNK_DATA)
				.findFirst()
				.orElseThrow();
		chunkPacket.position(1);
		int chunkX = chunkPacket.getInt();
		int chunkZ = chunkPacket.getInt();
		// In spiral order, first chunk is at (0, 0)
		assertEquals(0, chunkX);
		assertEquals(0, chunkZ);

		// Broadcast a live block update
		WorldStreamWsHandler.broadcastBlockDiff(10, 64, -20, "minecraft:gold_block", 3);

		assertTrue(diffLatch.await(5, TimeUnit.SECONDS));

		// Verify block diff packet
		ByteBuffer diffPacket = binaryPackets.stream()
				.filter(b -> b.get(0) == BinaryChunkSerializer.PACKET_BLOCK_DIFF)
				.findFirst()
				.orElseThrow();
		diffPacket.position(1);
		int x = diffPacket.getInt();
		int y = diffPacket.getInt();
		int z = diffPacket.getInt();
		int len = diffPacket.getShort();
		byte[] strBytes = new byte[len];
		diffPacket.get(strBytes);
		String blockState = new String(strBytes, StandardCharsets.UTF_8);
		int flags = diffPacket.getInt();

		assertEquals(10, x);
		assertEquals(64, y);
		assertEquals(-20, z);
		assertEquals("minecraft:gold_block", blockState);
		assertEquals(3, flags);

		ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
	}
}
