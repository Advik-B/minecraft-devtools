package dev.advik.mcdt;

import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.config.Permission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McdtCoreTest {

	@Test
	void testMcdtCoreLifecycle(@TempDir Path tempDir) throws Exception {
		McdtEnvironment env = new McdtEnvironment(
				"0.1.0-SNAPSHOT",
				"1.21.4",
				tempDir,
				tempDir.resolve("config"),
				tempDir.resolve("logs"),
				tempDir.resolve("home"),
				"DevPlayer",
				"not-installed",
				Instant.now()
		);

		// Pre-configure preferredPort to 0 (random) for the test
		Files.createDirectories(env.configDir());
		Files.writeString(env.configDir().resolve("config.json"), "{\"preferredPort\": 0, \"permission\": \"read\"}");

		MockGameClient mockClient = new MockGameClient();
		McdtCore core = McdtCore.start(env, Map.of(), mockClient);

		assertNotNull(core);
		assertSame(env, core.env());
		assertNotNull(core.config());
		assertEquals(Permission.READ, core.config().permission);
		assertNotNull(core.token());
		assertNotNull(core.queue());
		assertNotNull(core.server());
		assertNotNull(core.discovery());

		// Verify token was saved to disk
		assertTrue(Files.exists(env.configDir().resolve("token")));

		// Close core
		core.close();
	}
}
