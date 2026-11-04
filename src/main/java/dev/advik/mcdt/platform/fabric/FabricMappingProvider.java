//? if fabric {
package dev.advik.mcdt.platform.fabric;

import dev.advik.mcdt.reflection.MappingProvider;
import dev.advik.mcdt.reflection.NameService;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

/** Fabric runtime names: {@code named} in dev runs, {@code intermediary} in production. */
public final class FabricMappingProvider implements MappingProvider {
	/** Fabric's namespace for the obfuscated names that Mojang's mappings map to. */
	private static final String OFFICIAL_NAMESPACE = "official";

	@Override
	public boolean runtimeNamesAreMojang() {
		try {
			return "named".equals(FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace());
		} catch (Throwable ignored) {
			// Fabric Loader is not running (unit tests): treat names as Mojang names.
			return true;
		}
	}

	@Override
	public NameService.RuntimeNames officialToRuntime() {
		try {
			MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
			if (!resolver.getNamespaces().contains(OFFICIAL_NAMESPACE)) {
				return NameService.RuntimeNames.IDENTITY;
			}
			return new NameService.RuntimeNames() {
				@Override
				public String mapClass(String obfClass) {
					return resolver.mapClassName(OFFICIAL_NAMESPACE, obfClass);
				}

				@Override
				public String mapField(String obfOwner, String obfName, String obfDescriptor) {
					return resolver.mapFieldName(OFFICIAL_NAMESPACE, obfOwner, obfName, obfDescriptor);
				}

				@Override
				public String mapMethod(String obfOwner, String obfName, String obfDescriptor) {
					return resolver.mapMethodName(OFFICIAL_NAMESPACE, obfOwner, obfName, obfDescriptor);
				}
			};
		} catch (Throwable ignored) {
			return NameService.RuntimeNames.IDENTITY;
		}
	}
}
//?}
