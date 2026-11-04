plugins {
	id("xyz.wagyourtail.unimined")
}

fun prop(name: String): String = rootProject.property(name) as String

unimined.minecraft {
	version("1.12.2")

	mappings {
		searge()
		mcp("stable", prop("mcp1122"))
	}

	minecraftForge {
		loader(prop("forge1122_version"))
		mixinConfig("mcdtspike.mixins.json")
	}
}

dependencies {
	"modImplementation"("zone.rong:mixinbooter:${prop("mixinbooter_version")}")
}

tasks.jar {
	manifest.attributes(
		"FMLCorePluginContainsFMLMod" to true,
		"ForceLoadAsMod" to true,
		"MixinConfigs" to "mcdtspike.mixins.json",
		"TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
	)
}
