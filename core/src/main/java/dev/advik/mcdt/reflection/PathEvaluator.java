package dev.advik.mcdt.reflection;

import dev.advik.mcdt.http.Problems;

import java.lang.reflect.Array;
import java.util.*;

/**
 * Parses and evaluates path traversal queries on live objects.
 * Supports field access, zero-argument method calls (getters), and list/array/map indexing.
 * Example: {@code player.getInventory().items[0]}
 */
public final class PathEvaluator {
	private final ReflectionCache cache;

	public PathEvaluator(ReflectionCache cache) {
		this.cache = cache;
	}

	public Object evaluate(Object root, String path) {
		if (path == null || path.isBlank()) {
			return root;
		}

		if (root == null) {
			throw Problems.pathEvaluationFailed(path, "Cannot traverse path on null root object");
		}

		Object current = root;
		List<String> segments = parsePathSegments(path.trim());

		for (int i = 0; i < segments.size(); i++) {
			String segment = segments.get(i);
			if (current == null) {
				throw Problems.pathEvaluationFailed(path, "Path evaluated to null before segment '" + segment + "'");
			}

			current = evaluateSegment(current, segment, path);
		}

		return current;
	}

	private Object evaluateSegment(Object target, String segment, String fullPath) {
		if (segment.startsWith("[") && segment.endsWith("]")) {
			// Index access, e.g. [0] or ["key"]
			String indexContent = segment.substring(1, segment.length() - 1).trim();
			return evaluateIndex(target, indexContent, fullPath);
		}

		Class<?> targetClass = target.getClass();
		ReflectionSecurity.checkClass(targetClass);
		ReflectionCache.ClassMetadata metadata = cache.getMetadata(targetClass);

		if (segment.endsWith("()")) {
			// Zero-argument method call, e.g. getInventory()
			String methodName = segment.substring(0, segment.length() - 2).trim();
			return invokeZeroArgMethod(target, metadata, methodName, fullPath);
		}

		// Field or property access
		ReflectionCache.FieldMetadata field = metadata.getField(segment);
		if (field != null) {
			if (ReflectionSecurity.isSensitiveField(targetClass, field.resolvedName())) {
				return ReflectionSecurity.REDACTED;
			}
			try {
				return field.getValue(target);
			} catch (Throwable t) {
				throw Problems.pathEvaluationFailed(fullPath, "Failed reading field '" + segment + "': " + t.getMessage());
			}
		}

		// Try zero-arg getter: get<Name>(), is<Name>(), or <name>()
		String capitalized = Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
		ReflectionCache.MethodMetadata getter = metadata.getZeroArgMethod("get" + capitalized);
		if (getter == null) {
			getter = metadata.getZeroArgMethod("is" + capitalized);
		}
		if (getter == null) {
			getter = metadata.getZeroArgMethod(segment);
		}

		if (getter != null) {
			if (ReflectionSecurity.isSensitiveMethod(targetClass, getter.resolvedName())) {
				return ReflectionSecurity.REDACTED;
			}
			try {
				return getter.invoke(target);
			} catch (Throwable t) {
				throw Problems.pathEvaluationFailed(fullPath, "Failed invoking getter '" + getter.resolvedName() + "': " + t.getMessage());
			}
		}

		throw Problems.pathEvaluationFailed(fullPath, "No field or getter '" + segment + "' found on class '" + metadata.resolvedClassName() + "'");
	}

	private Object invokeZeroArgMethod(Object target, ReflectionCache.ClassMetadata metadata, String methodName, String fullPath) {
		// Deny dangerous Object methods
		if ("wait".equals(methodName) || "notify".equals(methodName) || "notifyAll".equals(methodName)) {
			throw Problems.reflectionDenied("Method '" + methodName + "()' is not allowed in path evaluation");
		}

		ReflectionCache.MethodMetadata method = metadata.getZeroArgMethod(methodName);
		if (method == null) {
			throw Problems.pathEvaluationFailed(fullPath, "Zero-argument method '" + methodName + "()' not found on class '" + metadata.resolvedClassName() + "'");
		}

		if (ReflectionSecurity.isSensitiveMethod(target.getClass(), method.resolvedName())) {
			return ReflectionSecurity.REDACTED;
		}
		try {
			return method.invoke(target);
		} catch (Throwable t) {
			throw Problems.pathEvaluationFailed(fullPath, "Error invoking method '" + methodName + "()': " + t.getMessage());
		}
	}

	private Object evaluateIndex(Object target, String indexExpr, String fullPath) {
		if (target == null) {
			throw Problems.pathEvaluationFailed(fullPath, "Cannot index null object");
		}

		// Check string key in map, e.g. ["key"] or ['key']
		if ((indexExpr.startsWith("\"") && indexExpr.endsWith("\"")) || (indexExpr.startsWith("'") && indexExpr.endsWith("'"))) {
			String key = indexExpr.substring(1, indexExpr.length() - 1);
			if (target instanceof Map<?, ?> map) {
				return map.get(key);
			}
			throw Problems.pathEvaluationFailed(fullPath, "String key indexing requires a Map, but got " + target.getClass().getName());
		}

		// Numeric index
		int index;
		try {
			index = Integer.parseInt(indexExpr);
		} catch (NumberFormatException e) {
			// Fallback: if Map, treat as map key
			if (target instanceof Map<?, ?> map) {
				return map.get(indexExpr);
			}
			throw Problems.pathEvaluationFailed(fullPath, "Invalid index expression: [" + indexExpr + "]");
		}

		if (index < 0) {
			throw Problems.pathEvaluationFailed(fullPath, "Index cannot be negative: " + index);
		}

		if (target instanceof List<?> list) {
			if (index >= list.size()) {
				throw Problems.pathEvaluationFailed(fullPath, "Index " + index + " out of bounds for list of size " + list.size());
			}
			return list.get(index);
		}

		if (target.getClass().isArray()) {
			int len = Array.getLength(target);
			if (index >= len) {
				throw Problems.pathEvaluationFailed(fullPath, "Index " + index + " out of bounds for array of length " + len);
			}
			return Array.get(target, index);
		}

		if (target instanceof Collection<?> col) {
			if (index >= col.size()) {
				throw Problems.pathEvaluationFailed(fullPath, "Index " + index + " out of bounds for collection of size " + col.size());
			}
			int i = 0;
			for (Object item : col) {
				if (i++ == index) {
					return item;
				}
			}
		}

		if (target instanceof Map<?, ?> map) {
			return map.get(index);
		}

		throw Problems.pathEvaluationFailed(fullPath, "Cannot apply index [" + index + "] to type " + target.getClass().getName());
	}

	/**
	 * Parses a path string such as {@code player.getInventory().items[0]} into tokens:
	 * {@code ["player", "getInventory()", "items", "[0]"]}
	 */
	public static List<String> parsePathSegments(String path) {
		List<String> segments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inBracket = false;

		for (int i = 0; i < path.length(); i++) {
			char c = path.charAt(i);

			if (c == '[') {
				if (!inBracket) {
					if (current.length() > 0) {
						segments.add(current.toString().trim());
						current.setLength(0);
					}
					inBracket = true;
					current.append(c);
				} else {
					current.append(c);
				}
			} else if (c == ']') {
				if (inBracket) {
					current.append(c);
					segments.add(current.toString().trim());
					current.setLength(0);
					inBracket = false;
				} else {
					current.append(c);
				}
			} else if (c == '.' && !inBracket) {
				if (current.length() > 0) {
					segments.add(current.toString().trim());
					current.setLength(0);
				}
			} else {
				current.append(c);
			}
		}

		if (current.length() > 0) {
			segments.add(current.toString().trim());
		}

		segments.removeIf(String::isBlank);
		return segments;
	}
}
