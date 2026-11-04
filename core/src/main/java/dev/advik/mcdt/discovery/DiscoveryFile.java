package dev.advik.mcdt.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.OwnerOnlyFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Discovery files let tools find a running client without hard-coding the port. One file per process goes to
 * {@code ~/.mcdt/instances/<pid>.json}, and a copy to {@code config/mcdt/instance.json}.
 */
public final class DiscoveryFile {
	private final List<Path> paths;
	private volatile boolean written;

	public DiscoveryFile(Path instancesDir, Path configDir, long pid) {
		this.paths = List.of(instancesDir.resolve(pid + ".json"), configDir.resolve("instance.json"));
	}

	public List<Path> paths() {
		return paths;
	}

	public void write(Info info) throws IOException {
		byte[] content;
		try {
			content = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(info).getBytes(StandardCharsets.UTF_8);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
		for (Path path : paths) {
			OwnerOnlyFiles.write(path, content);
		}
		written = true;
	}

	/** Deletes the files. Safe to call more than once and from a shutdown hook. */
	public void delete() {
		if (!written) {
			return;
		}
		for (Path path : paths) {
			try {
				Files.deleteIfExists(path);
			} catch (IOException e) {
				Mcdt.LOGGER.warn("Could not delete discovery file {}: {}", path, e.getMessage());
			}
		}
		written = false;
	}

	/** Contents of a discovery file. Field names are part of the public API. */
	public record Info(
			String baseUrl,
			int port,
			long pid,
			String mcdtVersion,
			String gameVersion,
			String gameDir,
			String playerName,
			Instant startTime,
			String token
	) {
		@Override
		public String toString() {
			return "Info[baseUrl=" + baseUrl + ", pid=" + pid + ", token=redacted]";
		}
	}
}
