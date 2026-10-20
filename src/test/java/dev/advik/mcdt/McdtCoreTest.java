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

	@Test
	void usesFixedTokenFromEnvironment(@TempDir Path tempDir) throws Exception {
		McdtEnvironment env = environment(tempDir);
		String fixed = "0123456789abcdef0123456789abcdef";

		McdtCore core = McdtCore.start(env, Map.of("MCDT_TOKEN", fixed, "MCDT_PORT", "0"), new MockGameClient());
		try {
			assertTrue(core.token().matches(fixed));
			assertEquals(fixed, Files.readString(env.configDir().resolve("token")));
		} finally {
			core.close();
		}
	}

	@Test
	void ignoresTooShortFixedToken(@TempDir Path tempDir) throws Exception {
		McdtEnvironment env = environment(tempDir);

		McdtCore core = McdtCore.start(env, Map.of("MCDT_TOKEN", "short", "MCDT_PORT", "0"), new MockGameClient());
		try {
			assertFalse(core.token().matches("short"));
		} finally {
			core.close();
		}
	}

	private static McdtEnvironment environment(Path tempDir) {
		return new McdtEnvironment(
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
	}
}
