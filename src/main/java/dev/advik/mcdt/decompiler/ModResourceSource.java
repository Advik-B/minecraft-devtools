package dev.advik.mcdt.decompiler;

import java.io.IOException;

/**
 * Reads a file from the installed mods' jars, for classes no classloader exposes as a resource. Found with
 * {@link java.util.ServiceLoader}; each platform jar registers its own in {@code META-INF/services}.
 */
public interface ModResourceSource {
	/** The bytes of {@code resourcePath} (for example {@code net/minecraft/class_310.class}), or null if absent. */
	byte[] read(String resourcePath) throws IOException;
}
