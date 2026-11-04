package dev.advik.mcdt.world.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Binary frames for {@code /v1/world/stream} (protocol version 2, big-endian). See {@code web/src/lib/viewer/protocol.ts}
 * for the matching decoder.
 */
public final class BinaryChunkSerializer {
	public static final int PROTOCOL_VERSION = 2;

	public static final byte PACKET_HELLO = 0x00;
	public static final byte PACKET_CHUNK = 0x01;
	public static final byte PACKET_UNLOAD = 0x03;
	public static final byte PACKET_POSE = 0x04;
	public static final byte PACKET_ENTITIES = 0x05;

	static final int CHUNK_FULL = 1;
	static final int CHUNK_TINT = 2;
	static final int SECTION_INDICES = 1;
	static final int SECTION_SKY = 2;
	static final int SECTION_BLOCK = 4;
	static final int SECTION_WIDE = 8;

	private BinaryChunkSerializer() {}

	/** Sent first on every session and again whenever the level changes. A null level means no world is loaded. */
	public static ByteBuffer encodeHello(ViewData.Level level) {
		byte[] dim = level != null ? utf8(level.dimension()) : new byte[0];
		ByteBuffer buf = allocate(1 + 2 + 1 + 13 + 2 + dim.length);
		buf.put(PACKET_HELLO);
		buf.putShort((short) PROTOCOL_VERSION);
		buf.put((byte) (level != null ? 1 : 0));
		if (level != null) {
			buf.putInt(level.levelId());
			buf.putInt(level.minY());
			buf.putInt(level.height());
			buf.put((byte) (level.hasSkyLight() ? 1 : 0));
			putString(buf, dim);
		}
		return buf.flip();
	}

	public static ByteBuffer encodeChunk(ViewData.Chunk chunk) {
		boolean tint = chunk.grassTint() != null && chunk.foliageTint() != null && chunk.waterTint() != null;
		int size = 1 + 4 + 4 + 1 + 2;
		for (ViewData.Section sec : chunk.sections()) {
			size += 2 + 1 + 2 + 4 * sec.palette().length;
			if (sec.indices() != null) size += sec.palette().length > 256 ? 8192 : 4096;
			if (sec.skyLight() != null) size += 2048;
			if (sec.blockLight() != null) size += 2048;
		}
		if (tint) size += 3 * 256 * 3;

		ByteBuffer buf = allocate(size);
		buf.put(PACKET_CHUNK);
		buf.putInt(chunk.chunkX());
		buf.putInt(chunk.chunkZ());
		buf.put((byte) ((chunk.full() ? CHUNK_FULL : 0) | (tint ? CHUNK_TINT : 0)));
		buf.putShort((short) chunk.sections().size());
		for (ViewData.Section sec : chunk.sections()) {
			boolean wide = sec.palette().length > 256;
			int flags = (sec.indices() != null ? SECTION_INDICES : 0)
					| (sec.skyLight() != null ? SECTION_SKY : 0)
					| (sec.blockLight() != null ? SECTION_BLOCK : 0)
					| (wide ? SECTION_WIDE : 0);
			buf.putShort((short) sec.sectionY());
			buf.put((byte) flags);
			buf.putShort((short) sec.palette().length);
			for (int id : sec.palette()) {
				buf.putInt(id);
			}
			if (sec.indices() != null) {
				if (wide) {
					for (short s : sec.indices()) buf.putShort(s);
				} else {
					for (short s : sec.indices()) buf.put((byte) s);
				}
			}
			if (sec.skyLight() != null) buf.put(sec.skyLight());
			if (sec.blockLight() != null) buf.put(sec.blockLight());
		}
		if (tint) {
			putRgb(buf, chunk.grassTint());
			putRgb(buf, chunk.foliageTint());
			putRgb(buf, chunk.waterTint());
		}
		return buf.flip();
	}

	public static ByteBuffer encodeUnload(int chunkX, int chunkZ) {
		ByteBuffer buf = allocate(9);
		buf.put(PACKET_UNLOAD);
		buf.putInt(chunkX);
		buf.putInt(chunkZ);
		return buf.flip();
	}

	public static ByteBuffer encodePose(ViewData.Pose pose) {
		ByteBuffer buf = allocate(1 + 24 + 8 + 8 + 4);
		buf.put(PACKET_POSE);
		buf.putDouble(pose.x());
		buf.putDouble(pose.y());
		buf.putDouble(pose.z());
		buf.putFloat(pose.yaw());
		buf.putFloat(pose.pitch());
		buf.putLong(pose.dayTime());
		buf.putFloat(pose.skyBrightness());
		return buf.flip();
	}

	public static ByteBuffer encodeEntities(List<ViewData.Entity> entities) {
		int count = Math.min(entities.size(), 0xFFFF);
		byte[][] types = new byte[count][];
		byte[][] names = new byte[count][];
		int size = 1 + 2;
		for (int i = 0; i < count; i++) {
			ViewData.Entity e = entities.get(i);
			types[i] = utf8(e.type());
			names[i] = utf8(e.name());
			size += 4 + 1 + 1 + 24 + 12 + 2 + types[i].length + 2 + names[i].length;
		}
		ByteBuffer buf = allocate(size);
		buf.put(PACKET_ENTITIES);
		buf.putShort((short) count);
		for (int i = 0; i < count; i++) {
			ViewData.Entity e = entities.get(i);
			buf.putInt(e.id());
			buf.put((byte) e.category());
			buf.put((byte) (e.self() ? 1 : 0));
			buf.putDouble(e.x());
			buf.putDouble(e.y());
			buf.putDouble(e.z());
			buf.putFloat(e.yaw());
			buf.putFloat(e.width());
			buf.putFloat(e.height());
			putString(buf, types[i]);
			putString(buf, names[i]);
		}
		return buf.flip();
	}

	private static ByteBuffer allocate(int size) {
		return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
	}

	private static byte[] utf8(String s) {
		return s != null ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
	}

	private static void putString(ByteBuffer buf, byte[] bytes) {
		buf.putShort((short) bytes.length);
		buf.put(bytes);
	}

	private static void putRgb(ByteBuffer buf, int[] colors) {
		for (int i = 0; i < 256; i++) {
			int c = colors[i];
			buf.put((byte) (c >> 16));
			buf.put((byte) (c >> 8));
			buf.put((byte) c);
		}
	}
}
