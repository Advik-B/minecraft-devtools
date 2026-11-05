plugins {
	id("net.neoforged.moddev")
	id("com.gradleup.shadow")
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

// The dev-run mod below reads the core and server source sets.
evaluationDependsOn(":core")
evaluationDependsOn(":server")

neoForge {
	version = prop("neoforge_version")
	// No Parchment for unobfuscated Minecraft (26.x): its own jar already has the names.
	if (project.hasProperty("parchment_version")) {
		parchment {
			minecraftVersion = mcVersion
			mappingsVersion = prop("parchment_version")
		}
	}
	// The platform tests use Minecraft types, as on Fabric.
	addModdingDependenciesTo(sourceSets.test.get())
	mods {
		register("mcdt") {
			sourceSet(sourceSets.main.get())
			// The built jar merges core and server in (see tasks.jar). Dev runs need them in the same mod module too:
			// they share packages with the platform code (dev.advik.mcdt.game, ...), and a split package hides them.
			for (module in listOf(":core", ":server")) {
				sourceSet(project(module).the<SourceSetContainer>().named("main").get())
			}
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
	dependencies.addAllLater(McdtBuild.bundledModules(project, bundled).map { deps -> deps.filterNot { McdtBuild.isRelocatedOnNeoForge(it.group) } })
}

// Jackson, Javalin and its OpenAPI plugins are merged into the mod jar with Jackson relocated (see shadowJar and
// McdtBuild.isRelocatedOnNeoForge), because Minecraft 1.21.2+ ships its own Jackson.
val relocated: Configuration = configurations.create("relocated") {
	isCanBeConsumed = false
	isCanBeResolved = true
	isTransitive = false
	dependencies.addAllLater(McdtBuild.bundledModules(project, bundled).map { deps -> deps.filter { McdtBuild.isRelocatedOnNeoForge(it.group) } })
}

dependencies {
	// The HTTP / MCP server and the shared core, with their libraries (Javalin, Jetty, Jackson, OpenAPI).
	bundled(project(":server")) {
		exclude(group = "org.slf4j")
		exclude(group = "org.ow2.asm")
	}

	// NeoForge provides slf4j and pins it strictly (2.0.9 on 1.21.1).
	testImplementation((testFixtures(project(":core")) as ModuleDependency).apply { exclude(group = "org.slf4j") })
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

// Dev runs before NeoForge 21.11: the bundled libraries must be game libraries too, or the mod module can't see them
// (the built jar nests or shades them instead). 21.11 has no separate classpath for this; plain dependencies work.
// Declared after `bundled` is filled, since MDG reads this configuration early.
if (stonecutter.eval(mcVersion, "<1.21.11")) {
	// Known gap: the OpenAPI plugin then can't see the generated spec, so /openapi.json is empty in these dev runs only.
	configurations.named("additionalRuntimeClasspath") {
		// Already the full resolved graph; resolving each module's POMs again would pull unpublished webjar deps.
		dependencies.addAllLater(McdtBuild.bundledModules(project, bundled).map { deps -> deps.onEach { (it as ModuleDependency).isTransitive = false } })
	}
}

tasks.processResources {
	val metadata = mapOf(
		"version" to project.version.toString(),
		"minecraft_version" to mcVersion,
		"neoforge_version" to prop("neoforge_version"),
		"baritone_version" to prop("baritone_version"),
		"java_version" to javaVersion.toString(),
	)
	inputs.properties(metadata)

	filesMatching(listOf("META-INF/neoforge.mods.toml", "mcdt.mixins.json")) {
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

// The core and server modules' classes and resources, merged into the mod jar (their libraries are nested or relocated).
fun CopySpec.mcdtModules() {
	for (module in listOf(":core", ":server")) {
		from(project(module).layout.buildDirectory.dir("classes/java/main"))
		from(project(module).layout.buildDirectory.dir("resources/main"))
	}
	from(rootProject.file("LICENSE")) {
		rename { "${it}_mcdt" }
	}
}

tasks.jar {
	// Without the relocated libraries; the mod jar is shadowJar.
	archiveClassifier = "slim"
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	dependsOn(":core:classes", ":server:classes")
	mcdtModules()
}

tasks.assemble {
	dependsOn(":server:exportOpenApi")
}

// The mod jar: MCDT, the jar-in-jar libraries (tasks.jarJar) and the relocated libraries.
tasks.shadowJar {
	archiveClassifier = ""
	configurations = listOf(relocated)
	dependsOn(":core:classes", ":server:classes")
	mcdtModules()
	from(tasks.named("jarJar"))
	relocate("com.fasterxml.jackson", McdtBuild.RELOCATED_JACKSON)
	mergeServiceFiles()
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	filesMatching("META-INF/services/**") {
		duplicatesStrategy = DuplicatesStrategy.INCLUDE
	}
	// Merged libraries must not turn the mod into a named module, and their versioned classes would not be relocated.
	exclude("module-info.class", "META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.assemble {
	dependsOn(tasks.shadowJar)
}

// Copies the mod jar to the root build/libs/, where CI, the Dockerfile and releases pick up every node.
val collectJar = tasks.register<Copy>("collectJar") {
	group = "build"
	description = "Copies this node's mod jar to the root build/libs/."
	from(tasks.shadowJar.flatMap { it.archiveFile })
	into(rootProject.layout.buildDirectory.dir("libs"))
}

tasks.build {
	finalizedBy(collectJar)
}
