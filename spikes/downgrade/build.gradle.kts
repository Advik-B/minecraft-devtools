import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

// Throwaway Phase 0 spike: compile real MCDT core sources and their unit tests, downgrade everything to
// Java 8 bytecode with JvmDowngrader, and run the tests on a Java 8 JVM.
plugins {
	java
	id("xyz.wagyourtail.jvmdowngrader") version "2.0.1"
}

repositories {
	mavenCentral()
}

val repoMain = rootDir.resolve("../../src/main/java")
val repoTest = rootDir.resolve("../../src/test/java")

// Packages with no Minecraft, Fabric or Javalin runtime types (see docs/MULTIVERSION.md, Phase 1 `core`).
sourceSets {
	main {
		java {
			setSrcDirs(listOf(repoMain))
			include(
				"dev/advik/mcdt/Mcdt.java",
				"dev/advik/mcdt/json/**",
				"dev/advik/mcdt/config/**",
				"dev/advik/mcdt/exec/**",
				"dev/advik/mcdt/events/EventEnvelope.java",
				"dev/advik/mcdt/events/EventRecord.java",
				"dev/advik/mcdt/events/EventRingBuffer.java",
				"dev/advik/mcdt/events/SubscriptionRequest.java",
				"dev/advik/mcdt/discovery/**",
				"dev/advik/mcdt/security/OwnerOnlyFiles.java",
				"dev/advik/mcdt/http/Problem.java",
				"dev/advik/mcdt/http/Problems.java",
				"dev/advik/mcdt/http/ProblemException.java",
				"dev/advik/mcdt/api/v1/dto/**",
				"dev/advik/mcdt/mcp/dto/**",
			)
		}
	}
	test {
		java {
			setSrcDirs(listOf(repoTest, file("src/test/java")))
			include(
				"dev/advik/mcdt/config/**",
				"dev/advik/mcdt/events/EventRingBufferTest.java",
				"dev/advik/mcdt/exec/**",
				"dev/advik/mcdt/discovery/**",
				"dev/advik/mcdt/spike/**",
			)
		}
	}
}

val jackson = "2.22.1"
val consoleStandalone: Configuration = configurations.create("consoleStandalone")

dependencies {
	implementation("com.fasterxml.jackson.core:jackson-databind:$jackson")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jackson")
	implementation("org.slf4j:slf4j-api:2.0.16")
	// Only @OpenApiName annotations; needed at runtime because Jackson reads class annotations.
	implementation("io.javalin.community.openapi:openapi-specification:7.2.3") {
		isTransitive = false
	}

	testImplementation(platform("org.junit:junit-bom:5.13.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
	consoleStandalone("org.junit.platform:junit-platform-console-standalone:1.13.4")
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
	options.encoding = "UTF-8"
}

tasks.test {
	useJUnitPlatform()
}

// One jar with the core classes, the tests and every runtime dependency, so it can be downgraded as a unit.
val fatTestJar = tasks.register<Jar>("fatTestJar") {
	archiveClassifier = "fat-tests"
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from(sourceSets.main.get().output, sourceSets.test.get().output)
	val runtime = configurations.testRuntimeClasspath.get()
	from({ (runtime + consoleStandalone).filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
		exclude("module-info.class", "META-INF/versions/**", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
	}
}

val downgradeFat = tasks.register<DowngradeJar>("downgradeFat") {
	inputFile = fatTestJar.flatMap { it.archiveFile }
	downgradeTo = JavaVersion.VERSION_1_8
	archiveClassifier = "fat-tests-java8"
}

val shadeFat = tasks.register<ShadeJar>("shadeFat") {
	inputFile = downgradeFat.flatMap { it.archiveFile }
	downgradeTo = JavaVersion.VERSION_1_8
	archiveClassifier = "fat-tests-java8-shaded"
}

// Runs the downgraded tests with the JUnit console launcher on a Java 8 JVM.
tasks.register<JavaExec>("testOnJava8") {
	group = "verification"
	dependsOn(shadeFat)
	javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(8) }
	classpath(shadeFat.flatMap { it.archiveFile })
	mainClass = "org.junit.platform.console.ConsoleLauncher"
	args("execute", "--select-package", "dev.advik.mcdt", "--details", "tree", "--fail-if-no-tests")
}
