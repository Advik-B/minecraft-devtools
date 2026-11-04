plugins {
	id("dev.kikugie.stonecutter")
	id("net.fabricmc.fabric-loom-remap") apply false
	id("net.neoforged.moddev") apply false
}

stonecutter active file(".sc_active_version")

// `//? if fabric {` / `//? if neoforge {` blocks in src/, chosen by the node name's suffix.
stonecutter parameters {
	constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge")
}

// Copies every node's mod jar to build/libs/, where CI, the Dockerfile and releases pick them up.
tasks.register("collectJars") {
	group = "build"
	description = "Copies every node's mod jar to build/libs/."
	dependsOn(stonecutter.versions.map { ":${it.project}:collectJar" })
}

tasks.register("exportOpenApi") {
	group = "documentation"
	description = "Exports the OpenAPI specification to build/libs/openapi.json."
	dependsOn(":server:exportOpenApi")
}
