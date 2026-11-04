// Shared setup for the Fabric node build scripts (build.fabric*.gradle.kts), free of Loom types so it works with both
// Loom plugins: Java and test setup, the `bundled` libraries, and merging the core and server modules into the mod jar.
plugins {
	java
	jacoco
}

val javaVersion = project.property("java_version").toString().toInt()

base.archivesName = "mcdt"

// Third-party libraries that ship inside the mod jar (jar-in-jar): everything the server module needs at runtime. The
// node script adds every resolved external module to its jar-in-jar configuration (McdtBuild.bundledModules). The
// core and server modules' own classes are merged into the mod jar instead (see tasks.jar).
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

dependencies {
	// The HTTP / MCP server and the shared core, with their libraries (Javalin, Jetty, Jackson, OpenAPI).
	bundled(project(":server"))
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
