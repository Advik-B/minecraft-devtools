package dev.advik.mcdt.reflection;

import dev.advik.mcdt.http.Problems;

import java.util.Locale;
import java.util.Set;

/**
 * Enforces reflection security by denying access to dangerous JVM classes and redacting sensitive session tokens.
 */
public final class ReflectionSecurity {
	public static final String REDACTED = "[REDACTED]";

	private static final Set<String> BLOCKED_EXACT_CLASSES = Set.of(
			"java.lang.Runtime",
			"java.lang.ProcessBuilder",
			"java.lang.Process",
			"java.lang.System",
			"java.lang.Thread",
			"java.lang.ThreadGroup",
			"sun.misc.Unsafe",
			"jdk.internal.misc.Unsafe",
			"java.io.File",
			"java.io.FileInputStream",
			"java.io.FileOutputStream",
			"java.io.RandomAccessFile",
			"java.io.FileReader",
			"java.io.FileWriter"
	);

	private static final Set<String> BLOCKED_PACKAGE_PREFIXES = Set.of(
			"java.lang.reflect.",
			"java.lang.invoke.",
			"java.nio.file.",
			"java.nio.channels.",
			"java.net.",
			"java.security.",
			"jdk.internal.",
			"sun."
	);

	private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
			"accesstoken",
			"sessiontoken",
			"token",
			"secrettoken",
			"xuid",
			"clientid"
	);

	private ReflectionSecurity() {
	}

	/**
	 * Checks whether the given class is permitted for reflection.
	 *
	 * @throws dev.advik.mcdt.http.ProblemException 403 Forbidden if denylisted.
	 */
	public static void checkClass(Class<?> clazz) {
		if (clazz == null) {
			return;
		}
		checkClassName(clazz.getName());
		if (ClassLoader.class.isAssignableFrom(clazz)) {
			throw Problems.reflectionDenied("ClassLoader inspection is blocked by the security denylist: " + clazz.getName());
		}
	}

	/**
	 * Checks whether the given class name is permitted.
	 *
	 * @throws dev.advik.mcdt.http.ProblemException 403 Forbidden if denylisted.
	 */
	public static void checkClassName(String className) {
		if (className == null || className.isBlank()) {
			return;
		}

		if (BLOCKED_EXACT_CLASSES.contains(className)) {
			throw Problems.reflectionDenied("Class '" + className + "' is blocked by the security denylist.");
		}

		for (String prefix : BLOCKED_PACKAGE_PREFIXES) {
			if (className.startsWith(prefix)) {
				throw Problems.reflectionDenied("Package '" + prefix + "*' is blocked by the security denylist.");
			}
		}
	}

	/**
	 * Determines whether a field contains sensitive credentials that must be redacted.
	 */
	public static boolean isSensitiveField(Class<?> declaringClass, String fieldName) {
		if (fieldName == null) {
			return false;
		}

		String lower = fieldName.toLowerCase(Locale.ROOT);
		if (SENSITIVE_FIELD_NAMES.contains(lower)) {
			return true;
		}

		if (declaringClass != null) {
			String className = declaringClass.getName();
			if (className.contains("User") || className.contains("authlib") || className.contains("Session")) {
				if (lower.contains("token") || lower.contains("xuid") || lower.contains("uuid") || lower.contains("secret")) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Returns true if the object value itself represents sensitive credentials.
	 */
	public static boolean isSensitiveObject(Object obj) {
		if (obj == null) {
			return false;
		}
		String name = obj.getClass().getName();
		if (name.equals("dev.advik.mcdt.security.BearerToken")) {
			return true;
		}
		if (name.equals("net.minecraft.client.User")) {
			return false; // Handled at field level or object redaction
		}
		return false;
	}
}
