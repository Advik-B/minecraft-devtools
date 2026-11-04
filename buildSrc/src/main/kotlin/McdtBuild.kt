import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider
import java.io.File
import java.net.URI

/** Build logic shared by the loader build scripts (build.<loader>.gradle.kts). */
object McdtBuild {
	/**
	 * Every external module in the resolved graph of [libraries], for the loader's jar-in-jar configuration (Loom's
	 * `include`, MDG's `jarJar`); neither is transitive. Walks the graph without artifacts, so project modules need not
	 * be built first. Skipped: slf4j and ASM, which the loaders provide (a second ASM conflicts with Mixin's and crashes
	 * startup with a LinkageError; NeoForge pins slf4j strictly), and compile-only jetbrains annotations.
	 */
	fun bundledModules(project: Project, libraries: Configuration): Provider<List<Dependency>> =
		libraries.incoming.resolutionResult.rootComponent.map { root ->
			val seen = mutableSetOf<ComponentIdentifier>()
			val found = mutableListOf<Dependency>()
			val queue = ArrayDeque(listOf(root))
			while (queue.isNotEmpty()) {
				for (dep in queue.removeFirst().dependencies) {
					val selected = (dep as? ResolvedDependencyResult)?.selected ?: continue
					if (!seen.add(selected.id)) continue
					queue.add(selected)
					val id = selected.id as? ModuleComponentIdentifier ?: continue
					if (isLoaderProvided(id.group, id.module)) continue
					found.add(project.dependencies.create("${id.group}:${id.module}:${id.version}"))
				}
			}
			found
		}

	fun isLoaderProvided(group: String, module: String): Boolean =
		group == "org.slf4j" || group == "org.ow2.asm" || (group == "org.jetbrains" && module == "annotations")

	/**
	 * The pinned Baritone API jar for a loader in `libs/`, downloaded again unless it matches [sha256]. Runs at
	 * configuration time because the jar is a compile dependency.
	 */
	fun baritoneJar(project: Project, loader: String, version: String, sha256: String): File {
		val libsDir = project.rootProject.file("libs")
		libsDir.mkdirs()
		val jarFile = libsDir.resolve(baritoneJarName(loader, version))
		if (jarFile.isFile && ChecksumUtils.sha256Of(jarFile.readBytes()) == sha256.lowercase()) {
			return jarFile
		}
		val url = baritoneUrl(loader, version)
		println("Downloading Baritone API jar from $url...")
		val bytes = URI(url).toURL().openStream().use { it.readBytes() }
		val actual = ChecksumUtils.sha256Of(bytes)
		if (actual != sha256.lowercase()) {
			throw GradleException("Checksum mismatch for $url: expected $sha256, got $actual")
		}
		jarFile.writeBytes(bytes)
		return jarFile
	}

	fun baritoneJarName(loader: String, version: String): String = "baritone-api-$loader-$version.jar"

	fun baritoneUrl(loader: String, version: String): String =
		"https://github.com/cabaletta/baritone/releases/download/v$version/${baritoneJarName(loader, version)}"
}
