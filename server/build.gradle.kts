// The HTTP / WebSocket / MCP server (Javalin 7 on Jetty 12, Java 17+), its handlers, docs and the WebUI assets.
// No Minecraft or mod loader types: the game is reached only through core's GameClient.
plugins {
	`java-library`
	jacoco
}

repositories {
	mavenCentral()
}

fun prop(name: String): String = providers.gradleProperty(name).get()

dependencies {
	api(project(":core"))
	api("io.javalin:javalin:${prop("javalin_version")}")
	api("io.javalin.community.openapi:javalin-openapi-plugin:${prop("javalin_openapi_version")}")
	api("io.javalin.community.openapi:javalin-redoc-plugin:${prop("javalin_openapi_version")}")
	annotationProcessor("io.javalin.community.openapi:openapi-annotation-processor:${prop("javalin_openapi_version")}")
	compileOnly("org.slf4j:slf4j-api:2.0.16")

	testImplementation(testFixtures(project(":core")))
	testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("io.javalin:javalin-testtools:${prop("javalin_version")}")
	testImplementation("org.slf4j:slf4j-api:2.0.16")
	testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
	testRuntimeOnly("org.ow2.asm:asm-commons:${prop("asm_version")}")
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

val webDir = rootProject.layout.projectDirectory.dir("web")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")

val installWeb = tasks.register<Exec>("installWeb") {
	group = "build setup"
	description = "Installs WebUI npm dependencies using Bun."
	workingDir = webDir.asFile
	commandLine = if (isWindows) listOf("cmd", "/c", "bun install --frozen-lockfile") else listOf("bun", "install", "--frozen-lockfile")
	inputs.file(webDir.file("package.json"))
	inputs.file(webDir.file("bun.lock"))
	outputs.dir(webDir.dir("node_modules"))
}

val buildWeb = tasks.register<Exec>("buildWeb") {
	group = "build setup"
	description = "Builds the Svelte WebUI dashboard using Bun."
	dependsOn(installWeb)
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
	from(webDir.dir("dist"))
	into(layout.buildDirectory.dir("generated/web/mcdt-web/dist"))
}

// The docs routes serve the mod icon; it lives with the mod metadata in the root project.
val syncIcons = tasks.register<Sync>("syncIcons") {
	from(rootProject.layout.projectDirectory.dir("src/main/resources")) {
		include("assets/mcdt/icon.png", "assets/mcdt/icon.svg")
	}
	into(layout.buildDirectory.dir("generated/icons"))
}

sourceSets.main {
	resources.srcDir(downloadScalar)
	resources.srcDir(syncIcons)
	resources.srcDir(layout.buildDirectory.dir("generated/web"))
}

tasks.processResources {
	dependsOn(syncWebAssets)
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
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

tasks.register<JavaExec>("runTestServer") {
	group = "verification"
	description = "Runs the standalone MCDT test server with WebUI and Baritone for manual and automated verification."
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("dev.advik.mcdt.TestServerRunner")
}

val exportOpenApi = tasks.register("exportOpenApi") {
	group = "documentation"
	description = "Exports the OpenAPI 3.1 specification to build/libs/openapi.json."
	dependsOn(tasks.compileJava)

	val classesDir = tasks.compileJava.flatMap { it.destinationDirectory }
	val outputFile = rootProject.layout.buildDirectory.file("libs/openapi.json")
	val projectVersion = provider { project.version.toString() }

	inputs.dir(classesDir)
	inputs.property("version", projectVersion)
	outputs.file(outputFile)

	doLast {
		val ver = projectVersion.get()
		val inputFile = classesDir.get().file("openapi-plugin/openapi-default.json").asFile
		if (!inputFile.isFile) {
			throw GradleException("OpenAPI definition not found at ${inputFile.absolutePath}")
		}
		val slurper = groovy.json.JsonSlurper()
		@Suppress("UNCHECKED_CAST")
		val json = slurper.parse(inputFile) as MutableMap<String, Any>

		@Suppress("UNCHECKED_CAST")
		val info = (json["info"] as? MutableMap<String, Any>) ?: mutableMapOf<String, Any>().also { json["info"] = it }
		info["title"] = "Minecraft DevTools API"
		info["version"] = ver
		info["description"] = "REST and WebSocket interface for the live Minecraft 1.21.4 client."

		@Suppress("UNCHECKED_CAST")
		val components = (json["components"] as? MutableMap<String, Any>) ?: mutableMapOf<String, Any>().also { json["components"] = it }
		@Suppress("UNCHECKED_CAST")
		val securitySchemes = (components["securitySchemes"] as? MutableMap<String, Any>) ?: mutableMapOf<String, Any>().also { components["securitySchemes"] = it }
		securitySchemes["BearerAuth"] = mapOf(
			"type" to "http",
			"scheme" to "bearer"
		)

		val target = outputFile.get().asFile
		target.parentFile.mkdirs()
		target.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)) + "\n")
	}
}
