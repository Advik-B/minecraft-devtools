package dev.advik.mcdt.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
	@TempDir
	Path tempDir;

	@Test
	void createsDefaultConfigWhenMissing() {
		Path file = tempDir.resolve("config.json");
		ConfigStore store = new ConfigStore(file);

		McdtConfig config = store.load();

		assertNotNull(config);
		assertTrue(config.enabled);
		assertEquals(25580, config.preferredPort);
		assertEquals("127.0.0.1", config.bindAddress);
		assertEquals(Permission.READ, config.permission);
		assertTrue(Files.exists(file));
	}

	@Test
	void preservesAndNormalizesSavedConfig() {
		Path file = tempDir.resolve("config.json");
		ConfigStore store = new ConfigStore(file);

		McdtConfig config = new McdtConfig();
		config.preferredPort = 30000;
		config.permission = Permission.ACT;
		store.save(config);

		McdtConfig reloaded = store.load();
		assertEquals(30000, reloaded.preferredPort);
		assertEquals(Permission.ACT, reloaded.permission);
	}

	@Test
	void backsUpCorruptedConfigAndFallsBackToDefaults() throws IOException {
		Path file = tempDir.resolve("config.json");
		Files.writeString(file, "{ this is invalid json !!!");

		ConfigStore store = new ConfigStore(file);
		McdtConfig config = store.load();

		assertNotNull(config);
		assertEquals(25580, config.preferredPort);

		Path backup = tempDir.resolve("config.json.bak");
		assertTrue(Files.exists(backup));
		assertEquals("{ this is invalid json !!!", Files.readString(backup));
	}

	@Test
	void respectsMcdtPortEnvironmentVariable() {
		McdtConfig config = new McdtConfig();
		config.preferredPort = 25580;

		assertEquals(29999, ConfigStore.effectivePort(config, Map.of(ConfigStore.PORT_ENV, "29999")));
		assertEquals(25580, ConfigStore.effectivePort(config, Map.of(ConfigStore.PORT_ENV, "not-a-number")));
		assertEquals(25580, ConfigStore.effectivePort(config, Map.of()));
	}
}
