import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.net.URI
import java.security.MessageDigest

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

loom {
	mods {
		register("mcdt") {
			sourceSet(sourceSets.main.get())
		}
	}
}

fun prop(name: String): String = providers.gradleProperty(name).get()

// Libraries that ship inside the mod jar (Loom jar-in-jar). Loom's `include` is not transitive,
// so every resolved artifact of this configuration is added to `include` below.
val bundled: Configuration = configurations.create("bundled") {
	isCanBeConsumed = false
	isCanBeResolved = true
}

configurations.implementation { extendsFrom(bundled) }

configurations.include {
	dependencies.addAllLater(bundled.incoming.artifacts.resolvedArtifacts.map { artifacts ->
		artifacts.mapNotNull { artifact ->
			val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
			// Exclude libraries provided by Minecraft or compile-only annotations from jar-in-jar.
			if (id.group == "org.slf4j" || (id.group == "org.jetbrains" && id.module == "annotations")) {
				return@mapNotNull null
			}
			project.dependencies.create("${id.group}:${id.module}:${id.version}")
		}
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

	// HTTP server, JSON, and OpenAPI generation.
	bundled("io.javalin:javalin:${prop("javalin_version")}")
	bundled("com.fasterxml.jackson.core:jackson-databind:${prop("jackson_version")}")
	bundled("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${prop("jackson_version")}")
	bundled("io.javalin.community.openapi:javalin-openapi-plugin:${prop("javalin_openapi_version")}")
	bundled("io.javalin.community.openapi:javalin-redoc-plugin:${prop("javalin_openapi_version")}")
	annotationProcessor("io.javalin.community.openapi:openapi-annotation-processor:${prop("javalin_openapi_version")}")

	testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("io.javalin:javalin-testtools:${prop("javalin_version")}")

	// Baritone API (optional, modCompileOnly)
	modCompileOnly(files("libs/baritone-api-fabric-${prop("baritone_version")}.jar"))
	testImplementation(files("libs/baritone-api-fabric-${prop("baritone_version")}.jar"))

	// Optional config UI: compile against it, run it in dev, never ship or require it.
	modCompileOnly("dev.isxander:yet-another-config-lib:${prop("yacl_version")}")
	modCompileOnly("com.terraformersmc:modmenu:${prop("modmenu_version")}")
	modLocalRuntime("dev.isxander:yet-another-config-lib:${prop("yacl_version")}")
	modLocalRuntime("com.terraformersmc:modmenu:${prop("modmenu_version")}")
}

/** Downloads a file and fails the build unless it matches a pinned SHA-256. */
abstract class DownloadVerified : DefaultTask() {
	@get:Input abstract val url: Property<String>
	@get:Input abstract val sha256: Property<String>
	@get:Input abstract val relativePath: Property<String>
	@get:OutputDirectory abstract val outputDir: DirectoryProperty

	@TaskAction
	fun download() {
		val target = outputDir.file(relativePath).get().asFile
		val expected = sha256.get().lowercase()
		if (target.isFile && sha256Of(target.readBytes()) == expected) return
		val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
		val actual = sha256Of(bytes)
		if (actual != expected) {
			throw GradleException("Checksum mismatch for ${url.get()}: expected $expected, got $actual")
		}
		target.parentFile.mkdirs()
		target.writeBytes(bytes)
	}

	private fun sha256Of(bytes: ByteArray): String =
		MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

val downloadScalar = tasks.register<DownloadVerified>("downloadScalar") {
	group = "build setup"
	description = "Downloads the pinned Scalar API reference browser bundle."
	val version = prop("scalar_version")
	url = "https://cdn.jsdelivr.net/npm/@scalar/api-reference@$version/dist/browser/standalone.js"
	sha256 = prop("scalar_sha256")
	relativePath = "mcdt-web/scalar/standalone.js"
	outputDir = layout.buildDirectory.dir("generated/scalar")
}

val downloadBaritoneApi = tasks.register<DownloadVerified>("downloadBaritoneApi") {
	group = "build setup"
	description = "Downloads the pinned Baritone API Fabric jar."
	val version = prop("baritone_version")
	url = "https://github.com/cabaletta/baritone/releases/download/v$version/baritone-api-fabric-$version.jar"
	sha256 = prop("baritone_sha256")
	relativePath = "baritone-api-fabric-$version.jar"
	outputDir = layout.projectDirectory.dir("libs")
}


val buildWeb = tasks.register<Exec>("buildWeb") {
	group = "build setup"
	description = "Builds the Svelte WebUI dashboard using Bun."

	val webDir = layout.projectDirectory.dir("web")
	val isWindows = System.getProperty("os.name").lowercase().contains("windows")

	workingDir = webDir.asFile
	commandLine = if (isWindows) listOf("cmd", "/c", "bun run build") else listOf("bun", "run", "build")

	inputs.dir(webDir.dir("src"))
	inputs.file(webDir.file("index.html"))
	inputs.file(webDir.file("package.json"))
	inputs.file(webDir.file("vite.config.ts"))
	outputs.dir(webDir.dir("dist"))
}

val syncWebAssets = tasks.register<Sync>("syncWebAssets") {
	group = "build setup"
	description = "Copies compiled WebUI assets into generated resources."
	dependsOn(buildWeb)
	from(layout.projectDirectory.dir("web/dist"))
	into(layout.buildDirectory.dir("generated/web/mcdt-web/dist"))
}

sourceSets.main {
	resources.srcDir(downloadScalar)
	resources.srcDir(layout.buildDirectory.dir("generated/web"))
}

tasks.compileJava {
	dependsOn(downloadBaritoneApi)
}

tasks.processResources {
	dependsOn(syncWebAssets)
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 21
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

	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
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
