package dev.advik.mcdt.reflection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches reflection metadata (fields, methods, MethodHandles, and resolved names) per class.
 */
public final class ReflectionCache {
	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	public record FieldMetadata(
			Field field,
			String resolvedName,
			String rawName,
			String typeName,
			boolean isStatic,
			boolean isFinal,
			MethodHandle getter
	) {
		public Object getValue(Object instance) throws Throwable {
			if (getter != null) {
				return isStatic ? getter.invoke() : getter.invoke(instance);
			}
			return field.get(instance);
		}
	}

	public record MethodMetadata(
			Method method,
			String resolvedName,
			String rawName,
			String returnType,
			List<String> parameterTypes,
			boolean isStatic,
			MethodHandle handle
	) {
		public Object invoke(Object instance, Object... args) throws Throwable {
			if (handle != null) {
				if (isStatic) {
					return handle.invokeWithArguments(args);
				} else {
					Object[] fullArgs = new Object[args.length + 1];
					fullArgs[0] = instance;
					System.arraycopy(args, 0, fullArgs, 1, args.length);
					return handle.invokeWithArguments(fullArgs);
				}
			}
			return method.invoke(instance, args);
		}
	}

	public static final class ClassMetadata {
		private final Class<?> clazz;
		private final String resolvedClassName;
		private final List<FieldMetadata> fields;
		private final List<MethodMetadata> methods;
		private final Map<String, FieldMetadata> fieldByName;
		private final Map<String, List<MethodMetadata>> methodsByName;

		ClassMetadata(
				Class<?> clazz,
				String resolvedClassName,
				List<FieldMetadata> fields,
				List<MethodMetadata> methods,
				Map<String, FieldMetadata> fieldByName,
				Map<String, List<MethodMetadata>> methodsByName
		) {
			this.clazz = clazz;
			this.resolvedClassName = resolvedClassName;
			this.fields = Collections.unmodifiableList(fields);
			this.methods = Collections.unmodifiableList(methods);
			this.fieldByName = Collections.unmodifiableMap(fieldByName);
			this.methodsByName = Collections.unmodifiableMap(methodsByName);
		}

		public Class<?> targetClass() {
			return clazz;
		}

		public String resolvedClassName() {
			return resolvedClassName;
		}

		public List<FieldMetadata> fields() {
			return fields;
		}

		public List<MethodMetadata> methods() {
			return methods;
		}

		public FieldMetadata getField(String name) {
			return fieldByName.get(name);
		}

		public List<MethodMetadata> getMethods(String name) {
			return methodsByName.getOrDefault(name, List.of());
		}

		public MethodMetadata getZeroArgMethod(String name) {
			List<MethodMetadata> overloads = methodsByName.get(name);
			if (overloads != null) {
				for (MethodMetadata m : overloads) {
					if (m.parameterTypes().isEmpty()) {
						return m;
					}
				}
			}
			return null;
		}
	}

	private final NameService nameService;
	private final Map<Class<?>, ClassMetadata> cache = new ConcurrentHashMap<>();

	public ReflectionCache(NameService nameService) {
		this.nameService = nameService;
	}

	public ClassMetadata getMetadata(Class<?> clazz) {
		if (clazz == null) {
			return null;
		}
		return cache.computeIfAbsent(clazz, this::computeMetadata);
	}

	private ClassMetadata computeMetadata(Class<?> clazz) {
		ReflectionSecurity.checkClass(clazz);

		String resolvedClassName = nameService.resolveClassName(clazz.getName());
		List<FieldMetadata> fieldList = new ArrayList<>();
		List<MethodMetadata> methodList = new ArrayList<>();
		Map<String, FieldMetadata> fieldMap = new HashMap<>();
		Map<String, List<MethodMetadata>> methodMap = new HashMap<>();

		// Walk up superclasses to include inherited members (excluding Object)
		Class<?> curr = clazz;
		Set<String> seenFieldNames = new HashSet<>();
		Set<String> seenMethodSignatures = new HashSet<>();

		while (curr != null && curr != Object.class) {
			for (Field field : curr.getDeclaredFields()) {
				try {
					field.setAccessible(true);
				} catch (Throwable ignored) {
				}

				String rawName = field.getName();
				String resolvedName = nameService.resolveFieldName(curr, rawName);
				String typeName = nameService.resolveClassName(field.getType().getName());
				boolean isStatic = Modifier.isStatic(field.getModifiers());
				boolean isFinal = Modifier.isFinal(field.getModifiers());

				MethodHandle getter = null;
				try {
					getter = LOOKUP.unreflectGetter(field);
				} catch (Throwable ignored) {
				}

				FieldMetadata meta = new FieldMetadata(field, resolvedName, rawName, typeName, isStatic, isFinal, getter);
				if (seenFieldNames.add(resolvedName)) {
					fieldList.add(meta);
				}
				fieldMap.putIfAbsent(resolvedName, meta);
				fieldMap.putIfAbsent(rawName, meta);
			}

			for (Method method : curr.getDeclaredMethods()) {
				try {
					method.setAccessible(true);
				} catch (Throwable ignored) {
				}

				String rawName = method.getName();
				String resolvedName = nameService.resolveMethodName(curr, rawName);
				String returnType = nameService.resolveClassName(method.getReturnType().getName());
				List<String> paramTypes = new ArrayList<>();
				for (Class<?> p : method.getParameterTypes()) {
					paramTypes.add(nameService.resolveClassName(p.getName()));
				}
				boolean isStatic = Modifier.isStatic(method.getModifiers());

				String sig = resolvedName + "(" + String.join(",", paramTypes) + ")";
				if (seenMethodSignatures.add(sig)) {
					MethodHandle handle = null;
					try {
						handle = LOOKUP.unreflect(method);
					} catch (Throwable ignored) {
					}

					MethodMetadata meta = new MethodMetadata(method, resolvedName, rawName, returnType, paramTypes, isStatic, handle);
					methodList.add(meta);
					methodMap.computeIfAbsent(resolvedName, k -> new ArrayList<>()).add(meta);
					methodMap.computeIfAbsent(rawName, k -> new ArrayList<>()).add(meta);
				}
			}

			curr = curr.getSuperclass();
		}

		return new ClassMetadata(clazz, resolvedClassName, fieldList, methodList, fieldMap, methodMap);
	}
}
