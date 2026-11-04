package dev.advik.mcdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class McdtEnvironmentTest {

	@Test
	void testEnvironmentProperties(@TempDir Path tempDir) {
		Instant now = Instant.now();
		McdtEnvironment env = new McdtEnvironment(
				"0.1.0",
				"1.21.4",
				tempDir,
				tempDir.resolve("config"),
				tempDir.resolve("logs"),
				tempDir.resolve("home"),
				"Alex",
				"1.10.2",
				now
		);

		assertEquals("0.1.0", env.mcdtVersion());
		assertEquals("1.21.4", env.gameVersion());
		assertEquals(tempDir, env.gameDir());
		assertEquals(tempDir.resolve("config"), env.configDir());
		assertEquals(tempDir.resolve("logs"), env.logDir());
		assertEquals(tempDir.resolve("home"), env.userHome());
		assertEquals("Alex", env.playerName());
		assertEquals("1.10.2", env.baritone());
		assertEquals(now, env.startTime());
		assertEquals(tempDir.resolve("home").resolve(".mcdt").resolve("instances"), env.instancesDir());
	}
}
