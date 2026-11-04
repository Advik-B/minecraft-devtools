pluginManagement {
	repositories {
		maven("https://maven.wagyourtail.xyz/releases")
		maven("https://maven.wagyourtail.xyz/snapshots")
		mavenCentral()
		gradlePluginPortal {
			content {
				excludeGroup("org.apache.logging.log4j")
			}
		}
	}
}

plugins {
	// Unimined runs some legacy tooling on a Java 8 toolchain; let Gradle download it.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcdt-legacy-spike"

include("forge189", "forge1122", "lf189", "lf1122")
