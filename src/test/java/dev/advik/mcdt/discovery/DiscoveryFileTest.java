package dev.advik.mcdt.discovery;

import dev.advik.mcdt.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryFileTest {
	@TempDir
	Path tempDir;

	@Test
	void writesAndDeletesDiscoveryFiles() throws IOException {
		Path instancesDir = tempDir.resolve("instances");
		Path configDir = tempDir.resolve("config");
		long pid = 12345;

		DiscoveryFile discovery = new DiscoveryFile(instancesDir, configDir, pid);

		DiscoveryFile.Info info = new DiscoveryFile.Info(
				"http://127.0.0.1:25580",
				25580,
				pid,
				"0.1.0-SNAPSHOT",
				"1.21.4",
				tempDir.toString(),
				"TestPlayer",
				Instant.now(),
				"secret-token"
		);

		discovery.write(info);

		Path instancePid = instancesDir.resolve("12345.json");
		Path configInstance = configDir.resolve("instance.json");

		assertTrue(Files.exists(instancePid));
		assertTrue(Files.exists(configInstance));

		DiscoveryFile.Info readInfo = Json.MAPPER.readValue(instancePid.toFile(), DiscoveryFile.Info.class);
		assertEquals(25580, readInfo.port());
		assertEquals("TestPlayer", readInfo.playerName());
		assertEquals("secret-token", readInfo.token());

		discovery.delete();

		assertFalse(Files.exists(instancePid));
		assertFalse(Files.exists(configInstance));
	}
}
