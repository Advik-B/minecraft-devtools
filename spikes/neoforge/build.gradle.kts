// Throwaway Phase 0 spike: do MCDT's bundled server libraries load from jar-in-jar on NeoForge (JPMS)?
plugins {
	java
	id("net.neoforged.moddev") version "2.0.148"
}

fun prop(name: String): String = property(name) as String

group = "dev.advik.mcdt.spike"
version = "0.0.0"
base.archivesName = "mcdt-spike-neoforge1211"

repositories {
	mavenCentral()
}

neoForge {
	version = prop("neoforge_version")
	mods {
		create("mcdtspike") {
			sourceSet(sourceSets.main.get())
		}
	}
}

// The same libraries the Fabric build bundles, minus what the loader already provides.
val bundledLibs = listOf(
	"io.javalin:javalin:${prop("javalin_version")}",
	"com.fasterxml.jackson.core:jackson-databind:${prop("jackson_version")}",
	"com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${prop("jackson_version")}",
	"io.javalin.community.openapi:javalin-openapi-plugin:${prop("javalin_openapi_version")}",
	"io.javalin.community.openapi:javalin-redoc-plugin:${prop("javalin_openapi_version")}",
)

dependencies {
	for (lib in bundledLibs) {
		implementation(lib) {
			// Minecraft provides slf4j (NeoForge pins it strictly) and ASM.
			exclude(group = "org.slf4j")
			exclude(group = "org.ow2.asm")
		}
	}
}

// MDG's jarJar is not transitive, so add every resolved runtime artifact of the libraries explicitly.
val bundledResolve: Configuration = configurations.create("bundledResolve") {
	isCanBeConsumed = false
	isCanBeResolved = true
}
dependencies {
	for (lib in bundledLibs) {
		bundledResolve(lib) {
			exclude(group = "org.slf4j")
			exclude(group = "org.ow2.asm")
		}
	}
}
configurations.named("jarJar") {
	dependencies.addAllLater(bundledResolve.incoming.artifacts.resolvedArtifacts.map { artifacts ->
		artifacts.mapNotNull { artifact ->
			val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
			if (id.group == "org.slf4j" || id.group == "org.ow2.asm" ||
				(id.group == "org.jetbrains" && id.module == "annotations")) {
				return@mapNotNull null
			}
			project.dependencies.create("${id.group}:${id.module}:${id.version}")
		}
	})
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 21
	options.encoding = "UTF-8"
}
