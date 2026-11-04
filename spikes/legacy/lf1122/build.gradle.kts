plugins {
	id("xyz.wagyourtail.unimined")
}

fun prop(name: String): String = rootProject.property(name) as String

unimined.minecraft {
	version("1.12.2")

	// Production stays in Legacy Fabric's intermediary; development uses the same MCP names as Forge.
	mappings {
		legacyIntermediary()
		searge()
		mcp("stable", prop("mcp1122"))
		devNamespace("mcp")
	}

	legacyFabric {
		loader(prop("legacy_fabric_loader"))
	}
}
