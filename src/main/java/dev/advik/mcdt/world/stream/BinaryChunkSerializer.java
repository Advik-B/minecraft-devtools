package dev.advik.mcdt.world.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Compact binary serializer for streaming chunk sections and live block diffs to 3D viewers.
 * Uses little-endian or big-endian binary frames over WebSocket.
 */
public final class BinaryChunkSerializer {
	public static final byte PACKET_CHUNK_DATA = 0x01;
	public static final byte PACKET_BLOCK_DIFF = 0x02;
	public static final byte PACKET_CHUNK_UNLOAD = 0x03;
	public static final byte PACKET_PLAYER_POSE = 0x04;

	public record SectionData(
			int sectionY,
			List<String> palette,
			byte[] blockIndices // 4096 bytes
	) {}

	/**
	 * Encodes a chunk column into a compact binary buffer.
	 */
	public static ByteBuffer encodeChunkData(int chunkX, int chunkZ, int minSectionY, List<SectionData> sections) {
		int estimatedSize = 1 + 4 + 4 + 2 + 2;
		for (SectionData sec : sections) {
			estimatedSize += 2 + 2;
			for (String pal : sec.palette()) {
				estimatedSize += 2 + pal.getBytes(StandardCharsets.UTF_8).length;
			}
			estimatedSize += (sec.blockIndices() != null ? sec.blockIndices().length : 0);
		}

		ByteBuffer buf = ByteBuffer.allocate(estimatedSize).order(ByteOrder.BIG_ENDIAN);
		buf.put(PACKET_CHUNK_DATA);
		buf.putInt(chunkX);
		buf.putInt(chunkZ);
		buf.putShort((short) minSectionY);
		buf.putShort((short) sections.size());

		for (SectionData sec : sections) {
			buf.putShort((short) sec.sectionY());
			buf.putShort((short) sec.palette().size());
			for (String pal : sec.palette()) {
				byte[] palBytes = pal.getBytes(StandardCharsets.UTF_8);
				buf.putShort((short) palBytes.length);
				buf.put(palBytes);
			}
			if (sec.blockIndices() != null) {
				buf.put(sec.blockIndices());
			}
		}

		buf.flip();
		return buf;
	}

	/**
	 * Encodes a single live block differential update into a compact binary buffer.
	 */
	public static ByteBuffer encodeBlockDiff(int x, int y, int z, String blockState, int flags) {
		byte[] stateBytes = (blockState != null ? blockState : "minecraft:air").getBytes(StandardCharsets.UTF_8);
		int size = 1 + 4 + 4 + 4 + 2 + stateBytes.length + 4;

		ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
		buf.put(PACKET_BLOCK_DIFF);
		buf.putInt(x);
		buf.putInt(y);
		buf.putInt(z);
		buf.putShort((short) stateBytes.length);
		buf.put(stateBytes);
		buf.putInt(flags);

		buf.flip();
		return buf;
	}

	/**
	 * Encodes a chunk unload notification.
	 */
	public static ByteBuffer encodeChunkUnload(int chunkX, int chunkZ) {
		ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
		buf.put(PACKET_CHUNK_UNLOAD);
		buf.putInt(chunkX);
		buf.putInt(chunkZ);
		buf.flip();
		return buf;
	}

	/**
	 * Encodes a player camera/pose update.
	 */
	public static ByteBuffer encodePlayerPose(double x, double y, double z, float yaw, float pitch) {
		ByteBuffer buf = ByteBuffer.allocate(1 + 8 + 8 + 8 + 4 + 4).order(ByteOrder.BIG_ENDIAN);
		buf.put(PACKET_PLAYER_POSE);
		buf.putDouble(x);
		buf.putDouble(y);
		buf.putDouble(z);
		buf.putFloat(yaw);
		buf.putFloat(pitch);
		buf.flip();
		return buf;
	}
}
