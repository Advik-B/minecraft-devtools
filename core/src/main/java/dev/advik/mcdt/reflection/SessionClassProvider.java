package dev.advik.mcdt.reflection;

/**
 * Names the game's login-session class (Minecraft's {@code User}) by type, so {@link ReflectionSecurity} can redact
 * it however it is named at runtime. Found with {@link java.util.ServiceLoader}; the platform jar registers one.
 */
public interface SessionClassProvider {
	Class<?> sessionClass();
}
