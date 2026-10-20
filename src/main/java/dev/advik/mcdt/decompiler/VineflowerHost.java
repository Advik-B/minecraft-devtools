package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.Mcdt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of the private isolated ClassLoader for Vineflower.
 * Vineflower classes and dependencies are loaded exclusively by this isolated ClassLoader
 * with the JVM Platform ClassLoader as parent, ensuring zero visibility or contamination to the game,
 * Fabric Loader, or other mods.
 */
public final class VineflowerHost implements AutoCloseable {
	private static final String EMBEDDED_JAR_RESOURCE = "/mcdt-internal/vineflower.jar";
	private static final String EMBEDDED_RENAMER_RESOURCE = "/mcdt-internal/VineflowerRenamer.classBytes";
	public static final String RENAMER_CLASS_NAME = "dev.advik.mcdt.decompiler.isolated.VineflowerRenamer";

	private final IsolatedClassLoader classLoader;
	private final Class<?> fernflowerClass;
	private final Class<?> bytecodeProviderClass;
	private final Class<?> resultSaverClass;
	private final Class<?> loggerClass;
	private final Class<?> renamerClass;
	private final AtomicBoolean closed = new AtomicBoolean(false);

	public VineflowerHost(Path cacheDir) throws IOException {
		Path jarPath = resolveVineflowerJar(cacheDir);
		this.classLoader = new IsolatedClassLoader(
				new URL[]{jarPath.toUri().toURL()},
				ClassLoader.getPlatformClassLoader()
		);

		try {
			this.fernflowerClass = Class.forName("org.jetbrains.java.decompiler.main.Fernflower", true, classLoader);
			this.bytecodeProviderClass = Class.forName("org.jetbrains.java.decompiler.main.extern.IBytecodeProvider", true, classLoader);
			this.resultSaverClass = Class.forName("org.jetbrains.java.decompiler.main.extern.IResultSaver", true, classLoader);
			this.loggerClass = Class.forName("org.jetbrains.java.decompiler.main.extern.IFernflowerLogger", true, classLoader);

			// Load the renamer class from embedded classBytes
			byte[] renamerBytes = loadRenamerBytes();
			if (renamerBytes != null && renamerBytes.length > 0) {
				this.renamerClass = classLoader.defineFromBytes(RENAMER_CLASS_NAME, renamerBytes);
			} else {
				this.renamerClass = null;
			}
			Mcdt.LOGGER.info("Vineflower decompiler host initialized successfully in isolated ClassLoader.");
		} catch (ClassNotFoundException e) {
			try {
				classLoader.close();
			} catch (IOException ignored) {
			}
			throw new IOException("Failed to initialize Vineflower classes inside isolated ClassLoader", e);
		}
	}

	private static Path resolveVineflowerJar(Path cacheDir) throws IOException {
		// 1. Try reading the embedded resource inside the mod JAR
		InputStream in = VineflowerHost.class.getResourceAsStream(EMBEDDED_JAR_RESOURCE);
		if (in != null) {
			Path target = cacheDir.resolve("jars").resolve("vineflower.jar");
			Files.createDirectories(target.getParent());
			try (in) {
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return target;
		}

		// 2. Fallback for test/dev environments where generated build resources may reside on disk
		Path devPath = Path.of("build/generated/vineflower/mcdt-internal/vineflower.jar");
		if (Files.isRegularFile(devPath)) {
			return devPath.toAbsolutePath();
		}

		throw new IOException("Vineflower embedded jar not found at " + EMBEDDED_JAR_RESOURCE + " or " + devPath);
	}

	private static byte[] loadRenamerBytes() {
		// 1. Try embedded resource
		try (InputStream in = VineflowerHost.class.getResourceAsStream(EMBEDDED_RENAMER_RESOURCE)) {
			if (in != null) {
				return in.readAllBytes();
			}
		} catch (IOException ignored) {
		}

		// 2. Fallback for dev / test environments
		Path devPath = Path.of("build/generated/vineflower/mcdt-internal/VineflowerRenamer.classBytes");
		if (Files.isRegularFile(devPath)) {
			try {
				return Files.readAllBytes(devPath);
			} catch (IOException ignored) {
			}
		}

		Path compiledDevClass = Path.of("build/generated/vineflower-classes/dev/advik/mcdt/decompiler/isolated/VineflowerRenamer.class");
		if (Files.isRegularFile(compiledDevClass)) {
			try {
				return Files.readAllBytes(compiledDevClass);
			} catch (IOException ignored) {
			}
		}

		return null;
	}

	public IsolatedClassLoader classLoader() {
		return classLoader;
	}

	public Class<?> fernflowerClass() {
		return fernflowerClass;
	}

	public Class<?> bytecodeProviderClass() {
		return bytecodeProviderClass;
	}

	public Class<?> resultSaverClass() {
		return resultSaverClass;
	}

	public Class<?> loggerClass() {
		return loggerClass;
	}

	public Class<?> renamerClass() {
		return renamerClass;
	}

	@Override
	public void close() throws IOException {
		if (closed.compareAndSet(false, true)) {
			classLoader.close();
		}
	}

	/**
	 * URLClassLoader subclass allowing definition of classes from byte arrays within this isolated scope.
	 */
	public static final class IsolatedClassLoader extends URLClassLoader {
		public IsolatedClassLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		public Class<?> defineFromBytes(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
