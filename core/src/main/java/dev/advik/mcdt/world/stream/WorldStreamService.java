package dev.advik.mcdt.world.stream;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.game.GameClient;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Drives {@code /v1/world/stream} sessions from the client tick. All game reads happen in {@link #tick}, which must run
 * on the client thread, and only snapshots state: frames are encoded and written on a dedicated sender thread, because a
 * WebSocket write (with permessage-deflate) costs milliseconds. WebSocket threads only add sessions and update their view.
 */
public final class WorldStreamService {
	public static final int DEFAULT_RADIUS = 8;
	public static final int MAX_RADIUS = 32;
	private static final long MAX_PENDING_BYTES = 8L * 1024 * 1024;
	private static final int MAX_QUEUED_FRAMES = 32;
	private static final int ENTITY_LIMIT = 512;
	private static final int LIGHT_SETTLE_TICKS = 10;
	private static final int NO_LEVEL = Integer.MIN_VALUE;

	private static volatile WorldStreamService active;

	private final GameClient game;
	private final List<Session> sessions = new CopyOnWriteArrayList<>();
	private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "MCDT-WorldStream-Send");
		thread.setDaemon(true);
		return thread;
	});
	// Client-thread state below.
	private final Map<Long, Set<Integer>> dirtyNow = new HashMap<>();
	private final Map<Long, Set<Integer>> dirtyLater = new HashMap<>();
	// Chunks that just loaded on the client: their light may not be applied yet, so streaming waits LIGHT_SETTLE_TICKS.
	private final Map<Long, Long> loadedAtTick = new HashMap<>();
	private long laterDueTick;
	private long chunkCostNanos = 1_000_000;
	private long tick;
	private int levelId = NO_LEVEL;
	private ViewData.Level level;

	public WorldStreamService(GameClient game) {
		this.game = game;
		active = this;
	}

	/** Sends one frame and calls {@code done} once it has left the process (or failed). */
	public interface Sink {
		void send(ByteBuffer frame, Runnable done);

		void close(int code, String reason);
	}

	public static final class Session {
		private final Sink sink;
		private final AtomicLong pendingBytes = new AtomicLong();
		private final AtomicInteger queuedFrames = new AtomicInteger();
		private volatile int requestedX;
		private volatile int requestedZ;
		private volatile boolean followPlayer = true;
		private volatile int radius;
		private volatile boolean entities = true;
		// Client-thread state.
		private boolean helloSent;
		private final Set<Long> sent = new HashSet<>();
		private int viewX = Integer.MIN_VALUE;
		private int viewZ = Integer.MIN_VALUE;
		private int viewRadius = -1;
		private List<ChunkOrderGenerator.ChunkOffset> order = List.of();

		Session(Sink sink, int radius) {
			this.sink = sink;
			this.radius = clampRadius(radius);
		}

		/** Centers the view on a chunk, or on the player when {@code follow} is true. */
		public void setView(Integer chunkX, Integer chunkZ, Integer radius, boolean follow) {
			if (chunkX != null && chunkZ != null) {
				this.requestedX = chunkX;
				this.requestedZ = chunkZ;
				this.followPlayer = follow;
			} else if (follow) {
				this.followPlayer = true;
			}
			if (radius != null) {
				this.radius = clampRadius(radius);
			}
		}

		public void setEntities(boolean entities) {
			this.entities = entities;
		}

		public long pendingBytes() {
			return pendingBytes.get();
		}

		boolean backlogged() {
			return queuedFrames.get() > MAX_QUEUED_FRAMES || pendingBytes.get() > MAX_PENDING_BYTES;
		}

		void send(ByteBuffer frame) {
			int size = frame.remaining();
			pendingBytes.addAndGet(size);
			try {
				sink.send(frame, () -> pendingBytes.addAndGet(-size));
			} catch (RuntimeException e) {
				pendingBytes.addAndGet(-size);
				throw e;
			}
		}
	}

	public Session open(Sink sink, int radius) {
		Session session = new Session(sink, radius);
		sessions.add(session);
		return session;
	}

	public void close(Session session) {
		sessions.remove(session);
	}

	public int sessionCount() {
		return sessions.size();
	}

	public void shutdown() {
		for (Session session : sessions) {
			try {
				session.sink.close(1001, "Server shutting down");
			} catch (RuntimeException ignored) {
			}
		}
		sessions.clear();
		sender.shutdownNow();
		if (active == this) {
			active = null;
		}
	}

	/** Client thread: a block changed, so resend its section now and it plus its face neighbors once light settles. */
	public static void onBlockChanged(int x, int y, int z) {
		WorldStreamService service = active;
		if (service != null && !service.sessions.isEmpty()) {
			service.markDirty(x >> 4, y >> 4, z >> 4);
		}
	}

	/** Client thread: a chunk (re)loaded, so every session resends it. */
	public static void onChunkLoaded(int chunkX, int chunkZ) {
		WorldStreamService service = active;
		if (service == null) return;
		long key = chunkKey(chunkX, chunkZ);
		if (!service.sessions.isEmpty()) {
			service.loadedAtTick.put(key, service.tick);
		}
		for (Session session : service.sessions) {
			session.sent.remove(key);
		}
	}

	/** Client thread: a chunk unloaded, so sessions that have it drop it. */
	public static void onChunkUnloaded(int chunkX, int chunkZ) {
		WorldStreamService service = active;
		if (service == null) return;
		long key = chunkKey(chunkX, chunkZ);
		service.loadedAtTick.remove(key);
		for (Session session : service.sessions) {
			if (session.sent.remove(key)) {
				service.trySend(session, BinaryChunkSerializer.encodeUnload(chunkX, chunkZ));
			}
		}
	}

	void markDirty(int chunkX, int sectionY, int chunkZ) {
		dirtyNow.computeIfAbsent(chunkKey(chunkX, chunkZ), k -> new HashSet<>()).add(sectionY);
		if (dirtyLater.isEmpty()) {
			laterDueTick = tick + LIGHT_SETTLE_TICKS;
		}
		Set<Integer> column = dirtyLater.computeIfAbsent(chunkKey(chunkX, chunkZ), k -> new HashSet<>());
		column.add(sectionY - 1);
		column.add(sectionY);
		column.add(sectionY + 1);
		dirtyLater.computeIfAbsent(chunkKey(chunkX - 1, chunkZ), k -> new HashSet<>()).add(sectionY);
		dirtyLater.computeIfAbsent(chunkKey(chunkX + 1, chunkZ), k -> new HashSet<>()).add(sectionY);
		dirtyLater.computeIfAbsent(chunkKey(chunkX, chunkZ - 1), k -> new HashSet<>()).add(sectionY);
		dirtyLater.computeIfAbsent(chunkKey(chunkX, chunkZ + 1), k -> new HashSet<>()).add(sectionY);
	}

	/** Client thread: advances every session within {@code budgetNanos}. */
	public void tick(long budgetNanos) {
		tick++;
		if (sessions.isEmpty()) {
			dirtyNow.clear();
			dirtyLater.clear();
			loadedAtTick.clear();
			return;
		}
		long deadline = System.nanoTime() + budgetNanos;

		ViewData.Level current = game.getViewLevel();
		int currentId = current != null ? current.levelId() : NO_LEVEL;
		if (currentId != levelId) {
			levelId = currentId;
			level = current;
			dirtyNow.clear();
			dirtyLater.clear();
			for (Session session : sessions) {
				session.helloSent = false;
			}
		}
		for (Session session : sessions) {
			if (!session.helloSent) {
				session.helloSent = true;
				session.sent.clear();
				session.viewRadius = -1;
				trySend(session, BinaryChunkSerializer.encodeHello(level));
			}
		}
		if (level == null) return;

		ViewData.Pose pose = game.getViewPose();
		if (pose != null && tick % 2 == 0) {
			ByteBuffer frame = BinaryChunkSerializer.encodePose(pose);
			for (Session session : sessions) {
				trySend(session, frame.duplicate());
			}
		}

		for (Session session : sessions) {
			updateView(session, pose);
		}

		if (tick % 4 == 0) {
			for (Session session : sessions) {
				if (!session.entities) continue;
				double cx = session.viewX * 16 + 8;
				double cz = session.viewZ * 16 + 8;
				List<ViewData.Entity> list = game.getViewEntities(cx, cz, session.viewRadius * 16 + 8, ENTITY_LIMIT);
				trySend(session, BinaryChunkSerializer.encodeEntities(list));
			}
		}

		flushDirty(dirtyNow, deadline);
		if (!dirtyLater.isEmpty() && tick >= laterDueTick) {
			flushDirty(dirtyLater, deadline);
		}

		streamChunks(deadline);
	}

	private void updateView(Session session, ViewData.Pose pose) {
		int x = session.requestedX;
		int z = session.requestedZ;
		if (session.followPlayer && pose != null) {
			x = (int) Math.floor(pose.x()) >> 4;
			z = (int) Math.floor(pose.z()) >> 4;
		}
		int r = session.radius;
		if (x == session.viewX && z == session.viewZ && r == session.viewRadius) return;
		if (r != session.viewRadius) {
			session.order = ChunkOrderGenerator.generate(r, ChunkOrderGenerator.Order.RADIAL);
		}
		session.viewX = x;
		session.viewZ = z;
		session.viewRadius = r;
		long r2 = (long) r * r;
		Iterator<Long> it = session.sent.iterator();
		while (it.hasNext()) {
			long key = it.next();
			int cx = chunkX(key);
			int cz = chunkZ(key);
			long dx = cx - x;
			long dz = cz - z;
			if (dx * dx + dz * dz > r2) {
				it.remove();
				trySend(session, BinaryChunkSerializer.encodeUnload(cx, cz));
			}
		}
	}

	/** Resends dirty sections until the deadline; whatever is left stays dirty for the next tick. */
	private void flushDirty(Map<Long, Set<Integer>> dirty, long deadline) {
		Iterator<Map.Entry<Long, Set<Integer>>> it = dirty.entrySet().iterator();
		while (it.hasNext() && System.nanoTime() < deadline) {
			Map.Entry<Long, Set<Integer>> entry = it.next();
			it.remove();
			long key = entry.getKey();
			Supplier<ByteBuffer> frame = null;
			for (Session session : sessions) {
				if (!session.sent.contains(key)) continue;
				if (frame == null) {
					ViewData.Chunk chunk = game.getViewChunk(chunkX(key), chunkZ(key), entry.getValue());
					if (chunk == null) break;
					frame = chunkFrame(chunk);
				}
				enqueue(session, frame);
			}
		}
	}

	/**
	 * Snapshots new chunks nearest-first. Stops when the next chunk would likely overrun the deadline, judged by a running
	 * average of snapshot cost, but always makes progress with at least one chunk per tick.
	 */
	private void streamChunks(long deadline) {
		boolean progressed = false;
		for (Session session : sessions) {
			for (ChunkOrderGenerator.ChunkOffset offset : session.order) {
				long now = System.nanoTime();
				if (progressed && now + chunkCostNanos > deadline) return;
				if (session.backlogged()) break;
				int cx = session.viewX + offset.dx();
				int cz = session.viewZ + offset.dz();
				long key = chunkKey(cx, cz);
				if (session.sent.contains(key) || !game.isChunkLoaded(cx, cz)) continue;
				if (settling(key)) continue;
				ViewData.Chunk chunk = game.getViewChunk(cx, cz, null);
				if (chunk == null) continue;
				chunkCostNanos = (chunkCostNanos * 7 + (System.nanoTime() - now)) / 8;
				progressed = true;
				session.sent.add(key);
				enqueue(session, chunkFrame(chunk));
			}
		}
	}

	private boolean settling(long key) {
		Long loadedAt = loadedAtTick.get(key);
		if (loadedAt == null) return false;
		if (tick - loadedAt < LIGHT_SETTLE_TICKS) return true;
		loadedAtTick.remove(key);
		return false;
	}

	private void trySend(Session session, ByteBuffer frame) {
		enqueue(session, () -> frame);
	}

	/** Encodes (if needed) and writes a frame on the sender thread. */
	private void enqueue(Session session, Supplier<ByteBuffer> frame) {
		session.queuedFrames.incrementAndGet();
		try {
			sender.execute(() -> {
				try {
					if (sessions.contains(session)) {
						session.send(frame.get());
					}
				} catch (RuntimeException e) {
					Mcdt.LOGGER.debug("Dropping world stream session: {}", e.getMessage());
					sessions.remove(session);
				} finally {
					session.queuedFrames.decrementAndGet();
				}
			});
		} catch (RejectedExecutionException e) {
			session.queuedFrames.decrementAndGet();
		}
	}

	/** Encodes a chunk once, on the sender thread, however many sessions it goes to. */
	private static Supplier<ByteBuffer> chunkFrame(ViewData.Chunk chunk) {
		return new Supplier<>() {
			private ByteBuffer encoded;

			@Override
			public ByteBuffer get() {
				if (encoded == null) {
					encoded = BinaryChunkSerializer.encodeChunk(chunk);
				}
				return encoded.duplicate();
			}
		};
	}

	static int clampRadius(int radius) {
		return Math.max(1, Math.min(MAX_RADIUS, radius));
	}

	static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	static int chunkX(long key) {
		return (int) (key >> 32);
	}

	static int chunkZ(long key) {
		return (int) key;
	}
}
