package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.http.Problems;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Resolves raw class bytecode (.class) for any requested class from the live game runtime,
 * including vanilla Minecraft classes, inner classes, and classes from any installed mod.
 */
public final class ClassBytecodeProvider {
	private static final List<ModResourceSource> MOD_SOURCES = loadModSources();

	private ClassBytecodeProvider() {
	}

	private static List<ModResourceSource> loadModSources() {
		try {
			return ServiceLoader.load(ModResourceSource.class, ClassBytecodeProvider.class.getClassLoader())
					.stream().map(ServiceLoader.Provider::get).toList();
		} catch (ServiceConfigurationError e) {
			Mcdt.LOGGER.warn("Could not load the platform mod resource source: {}", e.getMessage());
			return List.of();
		}
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

		// 4. Look in the installed mods' files through the platform (Fabric mod containers, ...).
		for (ModResourceSource source : MOD_SOURCES) {
			try {
				byte[] bytes = source.read(resourcePath.substring(1));
				if (bytes != null) {
					return bytes;
				}
			} catch (Throwable ignored) {
			}
		}

		throw Problems.classNotFound(runtimeClassName);
	}
}
