import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.net.URI
import java.security.MessageDigest

plugins {
	id("net.fabricmc.fabric-loom-remap")
	`maven-publish`
}

repositories {
	maven {
		name = "ParchmentMC"
		url = uri("https://maven.parchmentmc.org")
	}
	mavenCentral()
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("mcdt") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
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

sourceSets.main {
	resources.srcDir(downloadScalar)
}

tasks.processResources {
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
