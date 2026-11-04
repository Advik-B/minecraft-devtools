import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.net.URI

plugins {
	id("net.fabricmc.fabric-loom-remap")
	`maven-publish`
	jacoco
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
val javaVersion = prop("java_version").toInt()
val modVersion = rootProject.version.toString()

version = "$modVersion+$minecraftVersion-fabric"
base.archivesName = "mcdt"

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

fun ensureBaritoneJar(): File {
	val version = prop("baritone_version")
	val sha256 = prop("baritone_sha256")
	val libsDir = rootProject.file("libs")
	libsDir.mkdirs()
	val jarFile = libsDir.resolve("baritone-api-fabric-$version.jar")
	if (jarFile.isFile && ChecksumUtils.sha256Of(jarFile.readBytes()) == sha256.lowercase()) {
		return jarFile
	}
	val url = "https://github.com/cabaletta/baritone/releases/download/v$version/baritone-api-fabric-$version.jar"
	println("Downloading Baritone API Fabric jar from $url...")
	val bytes = URI(url).toURL().openStream().use { it.readBytes() }
	val actual = ChecksumUtils.sha256Of(bytes)
	if (actual != sha256.lowercase()) {
		throw GradleException("Checksum mismatch for $url: expected $sha256, got $actual")
	}
	jarFile.writeBytes(bytes)
	return jarFile
}

// Third-party libraries that ship inside the mod jar (Loom jar-in-jar): everything the server module needs at
// runtime. Loom's `include` is not transitive, so every resolved external artifact is added to `include` below.
// The core and server modules' own classes are merged into the mod jar instead (see tasks.jar).
val bundled: Configuration = configurations.create("bundled") {
	isCanBeConsumed = false
	isCanBeResolved = true
	attributes {
		attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
		attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
		attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
	}
}

configurations.implementation { extendsFrom(bundled) }

configurations.include {
	// Walks the resolved graph (no artifacts, so the project modules need not be built first) and includes every
	// external module. Excluded: libraries provided by Minecraft and compile-only annotations. ASM (pulled in by
	// Jetty) is provided by Fabric Loader; bundling a second copy causes a LinkageError against Mixin's ASM classes
	// and crashes the game at startup.
	dependencies.addAllLater(bundled.incoming.resolutionResult.rootComponent.map { root ->
		val seen = mutableSetOf<ComponentIdentifier>()
		val found = mutableListOf<Dependency>()
		val queue = ArrayDeque(listOf(root))
		while (queue.isNotEmpty()) {
			for (dep in queue.removeFirst().dependencies) {
				val selected = (dep as? ResolvedDependencyResult)?.selected ?: continue
				if (!seen.add(selected.id)) continue
				queue.add(selected)
				val id = selected.id as? ModuleComponentIdentifier ?: continue
				if (id.group == "org.slf4j" || id.group == "org.ow2.asm" ||
					(id.group == "org.jetbrains" && id.module == "annotations")) {
					continue
				}
				found.add(project.dependencies.create("${id.group}:${id.module}:${id.version}"))
			}
		}
		found
	})
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

	// The HTTP / MCP server and the shared core, with their libraries (Javalin, Jetty, Jackson, OpenAPI).
	bundled(project(":server"))

	testImplementation(testFixtures(project(":core")))
	testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Baritone API (optional, modCompileOnly)
	val baritoneJar = ensureBaritoneJar()
	modCompileOnly(files(baritoneJar))
	testImplementation(files(baritoneJar))

	// Optional config UI: compile against it, run it in dev, never ship or require it.
	modCompileOnly("dev.isxander:yet-another-config-lib:${prop("yacl_version")}")
	modCompileOnly("com.terraformersmc:modmenu:${prop("modmenu_version")}")
	modLocalRuntime("dev.isxander:yet-another-config-lib:${prop("yacl_version")}")
	modLocalRuntime("com.terraformersmc:modmenu:${prop("modmenu_version")}")
}

val downloadBaritoneApi = tasks.register<DownloadVerified>("downloadBaritoneApi") {
	group = "build setup"
	description = "Downloads the pinned Baritone API Fabric jar."
	val version = prop("baritone_version")
	url = "https://github.com/cabaletta/baritone/releases/download/v$version/baritone-api-fabric-$version.jar"
	sha256 = prop("baritone_sha256")
	relativePath = "baritone-api-fabric-$version.jar"
	outputDir = rootProject.layout.projectDirectory.dir("libs")
}

tasks.compileJava {
	dependsOn(downloadBaritoneApi)
}

tasks.processResources {
	val metadata = mapOf(
		"version" to project.version.toString(),
		"minecraft_version" to minecraftVersion,
		"java_version" to javaVersion.toString(),
		"baritone_version" to prop("baritone_version"),
	)
	inputs.properties(metadata)

	filesMatching("fabric.mod.json") {
		expand(metadata)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = javaVersion
	options.encoding = "UTF-8"
}


tasks.test {
	useJUnitPlatform()
	systemProperty("jdk.httpclient.allowRestrictedHeaders", "host,origin")
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required.set(true)
		html.required.set(true)
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
}

tasks.jar {
	val projectName = "mcdt"
	inputs.property("projectName", projectName)

	// One mod jar: the core and server modules' classes and resources are merged in (their libraries are jar-in-jar).
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	for (module in listOf(":core", ":server")) {
		dependsOn("$module:classes")
		from(project(module).layout.buildDirectory.dir("classes/java/main"))
		from(project(module).layout.buildDirectory.dir("resources/main"))
	}

	from(rootProject.file("LICENSE")) {
		rename { "${it}_$projectName" }
	}
}

tasks.assemble {
	dependsOn(":server:exportOpenApi")
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

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
