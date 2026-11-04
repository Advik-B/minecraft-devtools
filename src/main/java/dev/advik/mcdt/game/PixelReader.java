package dev.advik.mcdt.game;

import dev.advik.mcdt.http.Problems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.concurrent.TimeUnit;

//? if >=1.21.11 {
/*import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuFence;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
*///?} else {
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
//?}

/**
 * Reads pixels back from the GPU on the client thread: the main framebuffer (screenshots) and textures (atlases,
 * skins). Since 1.21.5 a readback is an asynchronous copy into a mapped buffer; this blocks the client thread until
 * the GPU finishes it (a few milliseconds), so callers keep a synchronous result.
 */
public final class PixelReader {
	/** How long to wait for a GPU copy before giving up. */
	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

	/** Pixels as ARGB ints, row by row from the top. */
	public record Image(int width, int height, int[] argb) {
	}

	private PixelReader() {
	}

	/** The main framebuffer, fully opaque. */
	public static Image screenshot(Minecraft client) {
		//? if >=1.21.11 {
		/*return read(client.getMainRenderTarget().getColorTexture(), true);
		*///?} else {
		NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
		try {
			return new Image(image.getWidth(), image.getHeight(), image.makePixelArray());
		} finally {
			image.close();
		}
		//?}
	}

	/** A loaded texture, or null if it has no GPU texture yet. */
	public static Image texture(AbstractTexture texture, int width, int height) {
		//? if >=1.21.11 {
		/*GpuTexture gpu = texture.getTexture();
		return gpu != null && !gpu.isClosed() ? read(gpu, false) : null;
		*///?} else {
		if (texture.getId() <= 0) {
			return null;
		}
		texture.bind();
		NativeImage image = new NativeImage(width, height, false);
		try {
			image.downloadTexture(0, false);
			return new Image(image.getWidth(), image.getHeight(), image.makePixelArray());
		} finally {
			image.close();
		}
		//?}
	}

	//? if >=1.21.11 {
	/*/^*
	 * Copies mip level 0 of a texture into a mapped buffer and converts it to ARGB. Framebuffers are stored bottom
	 * row first, so {@code framebuffer} flips the rows and drops the alpha channel.
	 ^/
	private static Image read(GpuTexture texture, boolean framebuffer) {
		int width = texture.getWidth(0);
		int height = texture.getHeight(0);
		int pixelSize = texture.getFormat().pixelSize();
		if (pixelSize != 4) {
			throw Problems.textureFailed("Unsupported texture format " + texture.getFormat() + " for " + texture.getLabel());
		}
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "MCDT readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
				(long) width * height * pixelSize);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		int[][] result = new int[1][];
		encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
			try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
				result[0] = toArgb(view.data(), width, height, framebuffer);
			} finally {
				buffer.close();
			}
		}, 0);

		// The copy's callback waits on a fence. Fence waits don't flush, so flush, then block on a fence placed after
		// the copy: the GPU runs commands in order, so once it signals, the copy's fence has signalled too.
		try (GpuFence fence = encoder.createFence()) {
			GL11.glFlush();
			if (!fence.awaitCompletion(TIMEOUT_NANOS)) {
				throw Problems.textureFailed("Timed out reading " + texture.getLabel() + " back from the GPU");
			}
		}
		RenderSystem.executePendingTasks();
		if (result[0] == null) {
			throw Problems.textureFailed("The GPU copy of " + texture.getLabel() + " did not complete");
		}
		return new Image(width, height, result[0]);
	}

	private static int[] toArgb(ByteBuffer data, int width, int height, boolean framebuffer) {
		int[] argb = new int[width * height];
		for (int y = 0; y < height; y++) {
			int srcRow = framebuffer ? height - 1 - y : y;
			for (int x = 0; x < width; x++) {
				int i = (srcRow * width + x) * 4;
				int r = data.get(i) & 0xFF;
				int g = data.get(i + 1) & 0xFF;
				int b = data.get(i + 2) & 0xFF;
				int a = framebuffer ? 0xFF : data.get(i + 3) & 0xFF;
				argb[y * width + x] = a << 24 | r << 16 | g << 8 | b;
			}
		}
		return argb;
	}
	*///?}
}
