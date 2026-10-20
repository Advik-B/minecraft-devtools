package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.reflection.NameService;
import dev.advik.mcdt.reflection.ReflectionSecurity;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Service that coordinates on-the-fly decompilation of Minecraft and installed mod classes.
 * Execution is offloaded to worker threads to avoid stalling the client tick thread.
 * Decompiled sources are cached in a thread-safe, bounded LRU cache.
 */
public final class DecompilerService implements AutoCloseable {
	private final McdtConfig config;
	private final VineflowerHost host;
	private final NameService nameService;
	private final Map<String, String> cache;

	public record DecompileResult(
			String className,
			String runtimeName,
			String source,
			boolean remapped,
			boolean cached,
			long durationMs
	) {
	}

	public DecompilerService(McdtConfig config, VineflowerHost host, NameService nameService) {
		this.config = config;
		this.host = host;
		this.nameService = nameService;
		this.cache = Collections.synchronizedMap(new LinkedHashMap<String, String>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
				return size() > Math.max(0, config.decompilerCacheSize);
			}
		});
	}

	/**
	 * Decompiles the specified class asynchronously off the client tick thread.
	 *
	 * @param requestedClassName the name of the class to decompile (e.g. {@code "net.minecraft.client.Minecraft"}).
	 * @param remap              whether to remap identifiers to Mojang human-readable names when mappings exist.
	 * @return a CompletableFuture yielding the {@link DecompileResult}.
	 */
	public CompletableFuture<DecompileResult> decompileAsync(String requestedClassName, boolean remap) {
		if (requestedClassName == null || requestedClassName.isBlank()) {
			throw Problems.invalidParameter("name", "Class name must not be blank.");
		}

		// Security boundary: deny inspection of forbidden system/runtime classes
		ReflectionSecurity.checkClassName(requestedClassName);

		if (!config.enableDecompiler) {
			throw Problems.decompilerDisabled();
		}

		String runtimeName = nameService.toRuntimeClassName(requestedClassName);
		String cacheKey = runtimeName + ":" + remap;

		String cachedSource = cache.get(cacheKey);
		if (cachedSource != null) {
			return CompletableFuture.completedFuture(new DecompileResult(
					requestedClassName,
					runtimeName,
					cachedSource,
					remap,
					true,
					0
			));
		}

		// Execute decompilation asynchronously off the game thread
		return CompletableFuture.supplyAsync(() -> {
			long start = System.currentTimeMillis();
			byte[] bytecode = ClassBytecodeProvider.findBytecode(runtimeName);
			String source = performDecompile(requestedClassName, runtimeName, bytecode, remap);
			long duration = System.currentTimeMillis() - start;

			if (config.decompilerCacheSize > 0) {
				cache.put(cacheKey, source);
			}

			return new DecompileResult(
					requestedClassName,
					runtimeName,
					source,
					remap,
					false,
					duration
			);
		});
	}

	private String performDecompile(String requestedName, String runtimeName, byte[] bytecode, boolean remap) {
		try {
			ClassLoader loader = host.classLoader();
			StringBuilder output = new StringBuilder();

			// Proxy for IBytecodeProvider
			Object bytecodeProvider = Proxy.newProxyInstance(
					loader,
					new Class<?>[]{host.bytecodeProviderClass()},
					(proxy, method, args) -> {
						if ("getBytecode".equals(method.getName())) {
							String ext = (String) args[0];
							String internal = (String) args[1];
							if (internal == null || internal.isBlank()) {
								return bytecode;
							}
							String targetName = internal.endsWith(".class") ? internal.substring(0, internal.length() - 6) : internal;
							if (targetName.replace('/', '.').equals(runtimeName)) {
								return bytecode;
							}
							if (targetName.startsWith("org/jetbrains/java/decompiler/") || targetName.startsWith("org/vineflower/")) {
								try (var in = loader.getResourceAsStream(internal.endsWith(".class") ? internal : internal + ".class")) {
									if (in != null) {
										return in.readAllBytes();
									}
								} catch (Exception ignored) {
								}
							}
							try {
								return ClassBytecodeProvider.findBytecode(targetName);
							} catch (Exception e) {
								return null;
							}
						}
						return null;
					}
			);

			// Proxy for IResultSaver
			Object resultSaver = Proxy.newProxyInstance(
					loader,
					new Class<?>[]{host.resultSaverClass()},
					(proxy, method, args) -> {
						String mName = method.getName();
						if ("saveClassFile".equals(mName) || "saveClassEntry".equals(mName)) {
							for (Object arg : args) {
								if (arg instanceof String s && (s.contains("class ") || s.contains("interface ") || s.contains("enum ") || s.contains("record "))) {
									output.append(s);
								}
							}
						}
						return null;
					}
			);

			// Logger
			Object logger = host.loggerClass().getField("NO_OP").get(null);

			Map<String, Object> props = new HashMap<>();
			props.put("dgs", "1"); // decompile generic signatures
			props.put("asc", "1"); // ascii string characters
			props.put("ren", "0"); // default no rename

			// Configure remapper if requested and renamer class is present
			if (remap && host.renamerClass() != null && !nameService.isDevEnvironment()) {
				configureRenamer(host.renamerClass());
				props.put("ren", "1");
				props.put("urc", VineflowerHost.RENAMER_CLASS_NAME);
			}

			// Instantiate Fernflower
			Object fernflower = host.fernflowerClass()
					.getConstructor(host.bytecodeProviderClass(), host.resultSaverClass(), Map.class, host.loggerClass())
					.newInstance(bytecodeProvider, resultSaver, props, logger);

			String simpleName = runtimeName.substring(runtimeName.lastIndexOf('.') + 1);
			File dummyFile = new File(simpleName + ".class");

			Method addSourceMethod = host.fernflowerClass().getMethod("addSource", File.class);
			addSourceMethod.invoke(fernflower, dummyFile);

			Method decompileMethod = host.fernflowerClass().getMethod("decompileContext");
			decompileMethod.invoke(fernflower);

			return output.toString();
		} catch (Exception e) {
			Mcdt.LOGGER.error("Failed to decompile class {}: {}", runtimeName, e.getMessage(), e);
			throw Problems.internal("Decompilation failed for " + requestedName + ": " + e.getMessage());
		}
	}

	private void configureRenamer(Class<?> renamerClass) {
		try {
			Field classRenamerField = renamerClass.getField("classRenamer");
			Field fieldRenamerField = renamerClass.getField("fieldRenamer");
			Field methodRenamerField = renamerClass.getField("methodRenamer");

			BiFunction<String, String, String> classRenamer = (fullName, shortName) -> {
				String dotted = fullName.replace('/', '.');
				String mapped = nameService.resolveClassName(dotted);
				if (!mapped.equals(dotted)) {
					int lastDot = mapped.lastIndexOf('.');
					return lastDot >= 0 ? mapped.substring(lastDot + 1) : mapped;
				}
				return null;
			};

			Function<String[], String> fieldRenamer = args -> {
				String className = args[0];
				String field = args[1];
				String mapped = nameService.resolveFieldByName(className.replace('/', '.'), field);
				return !mapped.equals(field) ? mapped : null;
			};

			Function<String[], String> methodRenamer = args -> {
				String className = args[0];
				String method = args[1];
				String mapped = nameService.resolveMethodByName(className.replace('/', '.'), method);
				return !mapped.equals(method) ? mapped : null;
			};

			classRenamerField.set(null, classRenamer);
			fieldRenamerField.set(null, fieldRenamer);
			methodRenamerField.set(null, methodRenamer);
		} catch (Exception e) {
			Mcdt.LOGGER.warn("Failed to configure Vineflower renamer: {}", e.getMessage());
		}
	}

	public void clearCache() {
		cache.clear();
	}

	@Override
	public void close() throws IOException {
		clearCache();
		host.close();
	}
}
