package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.reflection.NameService;
import dev.advik.mcdt.reflection.ReflectionSecurity;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

			// With remap, Vineflower only ever sees Mojang-named bytecode: the target class and every class it loads
			// for context are remapped here, and the names it asks for are Mojang names.
			MojangRemapper remapper = remap && !nameService.isDevEnvironment() ? new MojangRemapper(nameService) : null;
			byte[] mainBytes = remapper != null ? remapper.remap(bytecode) : bytecode;
			String mainName = remapper != null
					? remapper.map(runtimeName.replace('.', '/')).replace('/', '.')
					: runtimeName;

			// Proxy for IBytecodeProvider
			Object bytecodeProvider = Proxy.newProxyInstance(
					loader,
					new Class<?>[]{host.bytecodeProviderClass()},
					(proxy, method, args) -> {
						if ("getBytecode".equals(method.getName())) {
							String ext = (String) args[0];
							String internal = (String) args[1];
							if (internal == null || internal.isBlank()) {
								return mainBytes;
							}
							String targetName = internal.endsWith(".class") ? internal.substring(0, internal.length() - 6) : internal;
							if (targetName.replace('/', '.').equals(mainName)) {
								return mainBytes;
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
								if (remapper == null) {
									return ClassBytecodeProvider.findBytecode(targetName);
								}
								return remapper.remap(ClassBytecodeProvider.findBytecode(runtimeNameOf(targetName.replace('/', '.'))));
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
			props.put("ren", "0"); // names are already remapped above

			// Instantiate Fernflower
			Object fernflower = host.fernflowerClass()
					.getConstructor(host.bytecodeProviderClass(), host.resultSaverClass(), Map.class, host.loggerClass())
					.newInstance(bytecodeProvider, resultSaver, props, logger);

			String simpleName = mainName.substring(mainName.lastIndexOf('.') + 1);
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

	/**
	 * The runtime name of a Mojang class name. Anonymous and other unmapped nested classes keep the runtime suffix
	 * of their mapped outer class ({@code net.minecraft.client.Minecraft$1} is {@code net.minecraft.class_310$1}).
	 */
	private String runtimeNameOf(String mojangName) {
		String runtime = nameService.toRuntimeClassName(mojangName);
		int nested = mojangName.indexOf('$');
		if (!runtime.equals(mojangName) || nested < 0) {
			return runtime;
		}
		return nameService.toRuntimeClassName(mojangName.substring(0, nested)) + mojangName.substring(nested);
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
