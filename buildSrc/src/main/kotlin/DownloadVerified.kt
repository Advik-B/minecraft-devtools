import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.security.MessageDigest

object ChecksumUtils {
	fun sha256Of(bytes: ByteArray): String =
		MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
		if (target.isFile && ChecksumUtils.sha256Of(target.readBytes()) == expected) return
		val bytes = URI(url.get()).toURL().openStream().use { it.readBytes() }
		val actual = ChecksumUtils.sha256Of(bytes)
		if (actual != expected) {
			throw GradleException("Checksum mismatch for ${url.get()}: expected $expected, got $actual")
		}
		target.parentFile.mkdirs()
		target.writeBytes(bytes)
	}
}
