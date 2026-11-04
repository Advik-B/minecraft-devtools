//? if neoforge {
/*package dev.advik.mcdt.platform.neoforge;

import dev.advik.mcdt.decompiler.ModResourceSource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/^* Looks a resource up in every NeoForge mod file (Minecraft itself is one). ^/
public final class NeoForgeModResourceSource implements ModResourceSource {
	@Override
	public byte[] read(String resourcePath) throws IOException {
		ModList mods = ModList.get();
		if (mods == null) {
			return null;
		}
		for (IModFileInfo file : mods.getModFiles()) {
			//? if >=1.21.11 {
			/^byte[] bytes = file.getFile().getContents().readFile(resourcePath);
			if (bytes != null) {
				return bytes;
			}
			^///?} else {
			Path path = file.getFile().findResource(resourcePath.split("/"));
			if (path != null && Files.isRegularFile(path)) {
				return Files.readAllBytes(path);
			}
			//?}
		}
		return null;
	}
}
*///?}
