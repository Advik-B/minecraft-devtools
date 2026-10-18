package dev.advik.mcdt.reflection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NameServiceTest {

	@Test
	void testProGuardParsingAndResolution() throws Exception {
		String sampleMapping = """
				# Sample Mojang mapping file
				net.minecraft.client.Minecraft -> fgt:
				    net.minecraft.client.player.LocalPlayer player -> t
				    10:20:void setScreen(net.minecraft.client.gui.screens.Screen) -> a
				net.minecraft.client.Options -> fgu:
				    int renderDistance -> d
				""";

		NameService service = new NameService();
		service.loadFromReader(new BufferedReader(new StringReader(sampleMapping)));

		assertTrue(service.areMappingsLoaded());
		assertTrue(service.isDevEnvironment());

		// In dev environment, names pass through
		assertEquals("fgt", service.resolveClassName("fgt"));
		assertEquals("net.minecraft.client.Minecraft", service.toRuntimeClassName("net.minecraft.client.Minecraft"));
		assertEquals("t", service.resolveFieldName(Object.class, "t"));
		assertEquals("a", service.resolveMethodName(Object.class, "a"));
	}

	@Test
	void testProductionMappingResolution() throws Exception {
		String sampleMapping = """
				net.minecraft.client.Minecraft -> fgt:
				    net.minecraft.client.player.LocalPlayer player -> t
				    void setScreen(net.minecraft.client.gui.screens.Screen) -> a
				""";

		NameService service = new NameService();
		service.loadFromReader(new BufferedReader(new StringReader(sampleMapping)));

		// Switch to production mode via reflection to test mapping lookups
		Field devField = NameService.class.getDeclaredField("isDevEnvironment");
		devField.setAccessible(true);
		devField.set(service, false);

		assertFalse(service.isDevEnvironment());

		// Class translation
		assertEquals("net.minecraft.client.Minecraft", service.resolveClassName("fgt"));
		assertEquals("fgt", service.toRuntimeClassName("net.minecraft.client.Minecraft"));
		assertEquals("unmapped.Class", service.resolveClassName("unmapped.Class"));

		// Null argument handling
		assertNull(service.resolveClassName(null));
		assertNull(service.toRuntimeClassName(null));
		assertNull(service.resolveFieldName(null, null));
		assertNull(service.toRuntimeFieldName(null, null));
		assertNull(service.resolveMethodName(null, null));
		assertNull(service.toRuntimeMethodName(null, null));
	}

	@Test
	void testInitializeWithCachedFile(@TempDir Path tempDir) throws Exception {
		NameService service = new NameService();
		Field devField = NameService.class.getDeclaredField("isDevEnvironment");
		devField.setAccessible(true);
		devField.set(service, false);

		Path mappingsDir = tempDir.resolve("mappings");
		Files.createDirectories(mappingsDir);
		Path cachedFile = mappingsDir.resolve("1.21.4-client.txt");
		Files.writeString(cachedFile, """
				net.minecraft.client.Minecraft -> fgt:
				    int fps -> a
				""");

		service.initialize(tempDir, "1.21.4");
		assertTrue(service.areMappingsLoaded());

		// Second call is no-op
		service.initialize(tempDir, "1.21.4");
		assertTrue(service.areMappingsLoaded());
	}

	@Test
	void testFallbackWhenUnmapped() {
		NameService service = new NameService();
		assertEquals("com.example.Unknown", service.resolveClassName("com.example.Unknown"));
		assertEquals("unknownField", service.resolveFieldName(Object.class, "unknownField"));
		assertEquals("unknownMethod", service.resolveMethodName(Object.class, "unknownMethod"));
	}
}
