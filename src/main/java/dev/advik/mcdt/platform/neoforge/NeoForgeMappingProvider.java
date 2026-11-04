//? if neoforge {
/*package dev.advik.mcdt.platform.neoforge;

import dev.advik.mcdt.reflection.MappingProvider;
import dev.advik.mcdt.reflection.NameService;

/^* NeoForge runs with Mojang names, in dev and in production. ^/
public final class NeoForgeMappingProvider implements MappingProvider {
	@Override
	public boolean runtimeNamesAreMojang() {
		return true;
	}

	@Override
	public NameService.RuntimeNames officialToRuntime() {
		return NameService.RuntimeNames.IDENTITY;
	}
}
*///?}
