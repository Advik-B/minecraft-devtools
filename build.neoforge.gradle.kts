plugins {
	id("net.neoforged.moddev")
	jacoco
}

// One Stonecutter node (versions/<node>/gradle.properties holds its versions); sources come from the root src/.
// Node properties are project properties, not Gradle properties.
fun prop(name: String): String = project.property(name).toString()

val mcVersion = prop("minecraft_version")
val javaVersion = prop("java_version").toInt()
val modVersion = rootProject.version.toString()

version = "$modVersion+$mcVersion-neoforge"
base.archivesName = "mcdt"

repositories {
	maven {
		name = "Xander"
		url = uri("https://maven.isxander.dev/releases")
	}
	mavenCentral()
}

sourceSets.main { resources.srcDir(rootProject.file("src/neoforge/resources")) }

neoForge {
	version = prop("neoforge_version")
	parchment {
		minecraftVersion = mcVersion
		mappingsVersion = prop("parchment_version")
	}
	// The platform tests use Minecraft types, as on Fabric.
	addModdingDependenciesTo(sourceSets.test.get())
	mods {
		register("mcdt") {
			sourceSet(sourceSets.main.get())
		}
	}
	runs {
		register("client") {
			client()
			gameDirectory = rootProject.file("run")
			// Dev runs serve the dashboard straight from web/dist so `bun run build` shows up on reload
			systemProperty("mcdt.webDist", rootProject.layout.projectDirectory.dir("web/dist").asFile.absolutePath)
		}
	}
}

// Third-party libraries that ship inside the mod jar (MDG jar-in-jar): everything the server module needs at runtime.
// MDG's jarJar is not transitive, so every resolved external module is added to it below. The core and server modules'
// own classes are merged into the mod jar instead (see tasks.jar). NeoForge provides slf4j (pinned strictly) and ASM.
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

// Minecraft 1.21.4 ships Jackson 2.13.4 (for msal4j) and NeoForge pins it strictly; MCDT compiles against and bundles
// its own Jackson.
configurations.configureEach {
	resolutionStrategy.eachDependency {
		if (requested.group == "com.fasterxml.jackson.core") {
			val jackson = prop("jackson_version")
			useVersion(if (requested.name == "jackson-annotations") jackson.substringBeforeLast('.') else jackson)
		}
	}
}

configurations.named("jarJar") {
	dependencies.addAllLater(McdtBuild.bundledModules(project, bundled))
}

dependencies {
	// The HTTP / MCP server and the shared core, with their libraries (Javalin, Jetty, Jackson, OpenAPI).
	bundled(project(":server")) {
		exclude(group = "org.slf4j")
		exclude(group = "org.ow2.asm")
	}

	testImplementation(testFixtures(project(":core")))
	testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Baritone API (optional, compile-only)
	val baritoneJar = McdtBuild.baritoneJar(project, "neoforge", prop("baritone_version"), prop("baritone_sha256"))
	compileOnly(files(baritoneJar))
	testImplementation(files(baritoneJar))

	// Optional config UI: compile against it, never ship or require it.
	compileOnly("dev.isxander:yet-another-config-lib:${prop("yacl_version")}")
}

tasks.processResources {
	val metadata = mapOf(
		"version" to project.version.toString(),
		"minecraft_version" to mcVersion,
		"neoforge_version" to prop("neoforge_version"),
		"baritone_version" to prop("baritone_version"),
	)
	inputs.properties(metadata)

	filesMatching("META-INF/neoforge.mods.toml") {
		expand(metadata)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = javaVersion
	options.encoding = "UTF-8"
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
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

tasks.jar {
	// One mod jar: the core and server modules' classes and resources are merged in (their libraries are jar-in-jar).
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	for (module in listOf(":core", ":server")) {
		dependsOn("$module:classes")
		from(project(module).layout.buildDirectory.dir("classes/java/main"))
		from(project(module).layout.buildDirectory.dir("resources/main"))
	}

	from(rootProject.file("LICENSE")) {
		rename { "${it}_mcdt" }
	}
}

tasks.assemble {
	dependsOn(":server:exportOpenApi")
}

// Copies the mod jar to the root build/libs/, where CI, the Dockerfile and releases pick up every node.
val collectJar = tasks.register<Copy>("collectJar") {
	group = "build"
	description = "Copies this node's mod jar to the root build/libs/."
	from(tasks.jar.flatMap { it.archiveFile })
	into(rootProject.layout.buildDirectory.dir("libs"))
}

tasks.build {
	finalizedBy(collectJar)
}
