// Fabric nodes on obfuscated Minecraft (up to 1.21.11): Mojang mappings + Parchment, remapped to intermediary.
// Unobfuscated nodes (26.x) use build.fabric-unobf.gradle.kts. Shared setup: buildSrc/src/main/kotlin/mcdt.node.gradle.kts.
plugins {
	id("net.fabricmc.fabric-loom-remap")
	id("mcdt.node")
	`maven-publish`
}

repositories {
	maven {
		name = "ParchmentMC"
		url = uri("https://maven.parchmentmc.org")
	}
	maven {
		name = "Xander"
		url = uri("https://maven.isxander.dev/releases")
	}
	maven {
		name = "TerraformersMC"
		url = uri("https://maven.terraformersmc.com/releases/")
	}
	mavenCentral()
}

// One Stonecutter node (versions/<node>/gradle.properties holds its versions); sources come from the root src/.
val minecraftVersion = prop("minecraft_version")
val modVersion = rootProject.version.toString()

version = "$modVersion+$minecraftVersion-fabric"

sourceSets.main { resources.srcDir(rootProject.file("src/fabric/resources")) }

loom {
	mods {
		register("mcdt") {
			sourceSet(sourceSets.main.get())
		}
	}
	runs.configureEach {
		runDir("../../run")
		// Dev runs serve the dashboard straight from web/dist so `bun run build` shows up on reload
		vmArg("-Dmcdt.webDist=${rootProject.layout.projectDirectory.dir("web/dist").asFile.absolutePath}")
	}
}

// Node properties (versions/<node>/gradle.properties) are project properties, not Gradle properties.
fun prop(name: String): String = project.property(name).toString()

// Loom's `include` is not transitive, so every resolved external artifact of `bundled` (mcdt.node) is added to it.
configurations.include {
	dependencies.addAllLater(McdtBuild.bundledModules(project, configurations["bundled"]))
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${prop("minecraft_version")}")
	mappings(loom.layered {
		officialMojangMappings()
		parchment("org.parchmentmc.data:parchment-${prop("minecraft_version")}:${prop("parchment_version")}@zip")
	})
	modImplementation("net.fabricmc:fabric-loader:${prop("loader_version")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")

	implementation("org.slf4j:slf4j-api:2.0.16")
	testImplementation("org.slf4j:slf4j-api:2.0.16")
	testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")

	testImplementation(testFixtures(project(":core")))
	testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Baritone API (optional, modCompileOnly)
	val baritoneJar = McdtBuild.baritoneJar(project, "fabric", prop("baritone_version"), prop("baritone_sha256"))
	modCompileOnly(files(baritoneJar))
	testImplementation(files(baritoneJar))

	// Optional config UI: compile against it, run it in dev, never ship or require it. Nodes without YACL (1.16.5)
	// have no config screen.
	if (project.hasProperty("yacl_version")) {
		modCompileOnly("dev.isxander:yet-another-config-lib:${prop("yacl_version")}")
		modCompileOnly("com.terraformersmc:modmenu:${prop("modmenu_version")}")
		modLocalRuntime("dev.isxander:yet-another-config-lib:${prop("yacl_version")}")
		modLocalRuntime("com.terraformersmc:modmenu:${prop("modmenu_version")}")
	}
}

tasks.processResources {
	val metadata = mapOf(
		"version" to project.version.toString(),
		"minecraft_version" to minecraftVersion,
		"java_version" to prop("java_version"),
		"loader_version" to prop("loader_version"),
		"baritone_version" to prop("baritone_version"),
		// Mod Menu opens the YACL screen; without YACL there is no entrypoint.
		"modmenu_entrypoint" to if (project.hasProperty("yacl_version")) "\"dev.advik.mcdt.platform.fabric.FabricModMenuApi\"" else "",
	)
	inputs.properties(metadata)

	filesMatching(listOf("fabric.mod.json", "mcdt.mixins.json")) {
		expand(metadata)
	}
}

// Copies the mod jar to the root build/libs/, where CI, the Dockerfile and releases pick up every node.
val collectJar = tasks.register<Copy>("collectJar") {
	group = "build"
	description = "Copies this node's mod jar to the root build/libs/."
	from(tasks.remapJar.flatMap { it.archiveFile })
	into(rootProject.layout.buildDirectory.dir("libs"))
}

tasks.build {
	finalizedBy(collectJar)
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}
}
