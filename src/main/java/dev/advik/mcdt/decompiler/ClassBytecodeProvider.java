package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.http.Problems;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves raw class bytecode (.class) for any requested class from the live game runtime,
 * including vanilla Minecraft classes, inner classes, and classes from any installed mod.
 */
public final class ClassBytecodeProvider {
	private ClassBytecodeProvider() {
	}

	/**
	 * Fetches the bytecode bytes for a given runtime class name.
	 *
	 * @param runtimeClassName the internal or fully-qualified runtime class name (e.g. {@code "net.minecraft.class_310"} or {@code "me.author.mod.SomeClass"}).
	 * @return byte array of the class bytecode.
	 * @throws dev.advik.mcdt.http.ProblemException with 404 if the class bytecode cannot be found.
	 */
	public static byte[] findBytecode(String runtimeClassName) {
		String resourcePath = runtimeClassName.replace('.', '/') + ".class";
		if (!resourcePath.startsWith("/")) {
			resourcePath = "/" + resourcePath;
		}

		// 1. Try Class.forName and getResourceAsStream on its classloader
		try {
			Class<?> clazz = Class.forName(runtimeClassName.replace('/', '.'), false, Thread.currentThread().getContextClassLoader());
			ClassLoader cl = clazz.getClassLoader();
			if (cl != null) {
				try (InputStream in = cl.getResourceAsStream(resourcePath.substring(1))) {
					if (in != null) {
						return in.readAllBytes();
					}
				}
			}
		} catch (Throwable ignored) {
		}

		// 2. Try Thread context ClassLoader directly
		ClassLoader ctxLoader = Thread.currentThread().getContextClassLoader();
		if (ctxLoader != null) {
			try (InputStream in = ctxLoader.getResourceAsStream(resourcePath.substring(1))) {
				if (in != null) {
					return in.readAllBytes();
				}
			} catch (IOException ignored) {
			}
		}

		// 3. Try System / Mod ClassLoader
		try (InputStream in = ClassBytecodeProvider.class.getResourceAsStream(resourcePath)) {
			if (in != null) {
				return in.readAllBytes();
			}
		} catch (IOException ignored) {
		}

		// 4. Try Fabric mod containers (find file in mod jars)
		try {
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				try {
					var originPath = mod.findPath(resourcePath.substring(1));
					if (originPath.isPresent() && Files.isRegularFile(originPath.get())) {
						return Files.readAllBytes(originPath.get());
					}
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
			// FabricLoader might not be initialized in non-fabric unit tests
		}

		throw Problems.classNotFound(runtimeClassName);
	}
}
