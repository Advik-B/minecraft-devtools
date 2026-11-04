package dev.advik.mcdt.reflection;

/**
 * How the running mod loader names Minecraft classes at runtime. {@link NameService} finds one with
 * {@link java.util.ServiceLoader}; each platform jar registers its own in {@code META-INF/services}.
 * Without one (unit tests) runtime names are taken to be Mojang names.
 */
public interface MappingProvider {
	/** True when runtime names already are Mojang names (dev runs, NeoForge, unobfuscated versions). */
	boolean runtimeNamesAreMojang();

	/** Maps obfuscated ({@code official}) names to runtime names. Only used when names are not Mojang names. */
	NameService.RuntimeNames officialToRuntime();
}
