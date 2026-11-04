package dev.advik.mcdt.platform.fabric;

import dev.advik.mcdt.decompiler.ModResourceSource;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Looks a resource up in every Fabric mod container. */
public final class FabricModResourceSource implements ModResourceSource {
	@Override
	public byte[] read(String resourcePath) throws IOException {
		try {
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				Optional<Path> path = mod.findPath(resourcePath);
				if (path.isPresent() && Files.isRegularFile(path.get())) {
					return Files.readAllBytes(path.get());
				}
			}
		} catch (IllegalStateException | NoClassDefFoundError ignored) {
			// Fabric Loader is not running (unit tests).
		}
		return null;
	}
}
