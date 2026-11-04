pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		maven {
			name = "NeoForged"
			url = uri("https://maven.neoforged.net/releases")
		}
		maven {
			name = "KikuGie"
			url = uri("https://maven.kikugie.dev/releases")
		}
		mavenCentral()
		gradlePluginPortal()
	}

	plugins {
		id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version")
		id("net.neoforged.moddev") version providers.gradleProperty("moddev_version")
		id("com.gradleup.shadow") version providers.gradleProperty("shadow_version")
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.8"
}

rootProject.name = "mcdt"

// Loader-neutral projects: no Minecraft types (see AGENTS.md).
include("core", "server")

// The platform: one node per Minecraft version and loader, all built from src/ (see stonecutter.gradle.kts).
stonecutter {
	create(rootProject) {
		version("1.21.1-fabric", "1.21.1").buildscript = "build.fabric.gradle.kts"
		version("1.21.4-fabric", "1.21.4").buildscript = "build.fabric.gradle.kts"
		version("1.21.4-neoforge", "1.21.4").buildscript = "build.neoforge.gradle.kts"
		vcsVersion = "1.21.4-fabric"
	}
}
