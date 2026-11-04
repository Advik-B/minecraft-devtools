// Throwaway Phase 0 spike: one MCP-named source tree built for Forge and Legacy Fabric with Unimined.
plugins {
	id("xyz.wagyourtail.unimined") version "1.4.1" apply false
}

subprojects {
	apply(plugin = "java")

	group = "dev.advik.mcdt.spike"
	version = "0.0.0"

	repositories {
		mavenCentral()
		maven("https://maven.cleanroommc.com/")
		maven("https://repo.spongepowered.org/maven/")
	}

	extensions.configure<JavaPluginExtension> {
		sourceSets.named("main") {
			java.srcDir(rootProject.file("shared/java"))
			java.srcDir(rootProject.file("mcp/java"))
			resources.srcDir(rootProject.file("mcp/resources"))
		}
	}

	tasks.withType<JavaCompile>().configureEach {
		options.release = 8
		options.encoding = "UTF-8"
	}

	extensions.configure<BasePluginExtension> {
		archivesName = "mcdt-spike-${project.name}"
	}
}
