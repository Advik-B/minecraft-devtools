// Container startup for the Docker image: checks the settings, installs the bundled and user mods
// (mounted /mods, MC_MODS URLs, MC_MODRINTH projects) and seeds options.txt. Runs before the game starts.
plugins {
	java
	id("com.gradleup.shadow")
}

repositories {
	mavenCentral()
}

fun prop(name: String): String = providers.gradleProperty(name).get()

dependencies {
	implementation("com.fasterxml.jackson.core:jackson-databind:${prop("jackson_version")}")

	testImplementation(platform("org.junit:junit-bom:${prop("junit_version")}"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 17
	options.encoding = "UTF-8"
}

tasks.jar {
	manifest.attributes("Main-Class" to "dev.advik.mcdt.docker.Setup")
}

tasks.shadowJar {
	archiveFileName = "mcdt-docker-setup.jar"
	manifest.attributes("Main-Class" to "dev.advik.mcdt.docker.Setup")
	exclude("META-INF/versions/*/module-info.class", "module-info.class")
}

tasks.test {
	useJUnitPlatform()
}
