// Code with no Minecraft, mod loader or HTTP-server types: config, task queue, events, reflection engine,
// decompiler, MCP tool logic, DTOs and the GameClient interface. Shared by every platform build.
plugins {
	`java-library`
	`java-test-fixtures`
	jacoco
}

repositories {
	mavenCentral()
}

fun prop(name: String): String = providers.gradleProperty(name).get()

dependencies {
	api("com.fasterxml.jackson.core:jackson-databind:${prop("jackson_version")}")
	api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${prop("jackson_version")}")
	// Provided at runtime: slf4j and ASM by Minecraft / the mod loader, @OpenApiName by the server module's plugin.
	compileOnlyApi("org.slf4j:slf4j-api:2.0.16")
	compileOnlyApi("io.javalin.community.openapi:openapi-specification:${prop("javalin_openapi_version")}") {
		isTransitive = false
	}
	compileOnly("org.ow2.asm:asm-commons:${prop("asm_version")}")

	testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.slf4j:slf4j-api:2.0.16")
	testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
	testImplementation("org.ow2.asm:asm-commons:${prop("asm_version")}")
}

val downloadVineflower = tasks.register<DownloadVerified>("downloadVineflower") {
	group = "build setup"
	description = "Downloads the pinned Vineflower decompiler jar for isolated runtime loading."
	val version = prop("vineflower_version")
	url = "https://repo1.maven.org/maven2/org/vineflower/vineflower/$version/vineflower-$version.jar"
	sha256 = prop("vineflower_sha256")
	relativePath = "mcdt-internal/vineflower.jar"
	outputDir = layout.buildDirectory.dir("generated/vineflower")
}

sourceSets.main {
	resources.srcDir(downloadVineflower)
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
	options.encoding = "UTF-8"
}

tasks.test {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required.set(true)
		html.required.set(true)
	}
}
