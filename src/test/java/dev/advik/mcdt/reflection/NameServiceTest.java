package dev.advik.mcdt.reflection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

	@Test
	void translatesObfuscatedNamesToRuntimeNamesUsingDescriptors() throws Exception {
		String sampleMapping = """
				net.minecraft.client.Minecraft -> fgt:
				    net.minecraft.client.player.LocalPlayer player -> t
				    int[][] counters -> u
				    10:20:void setScreen(net.minecraft.client.gui.screens.Screen):30:31 -> a
				    boolean isPaused() -> a
				    1:2:void <init>() -> <init>
				net.minecraft.client.player.LocalPlayer -> fhx:
				net.minecraft.client.gui.screens.Screen -> fjz:
				""";
		// Fake Fabric resolver: only answers when the obfuscated descriptor is exactly right. The runtime class is a
		// real class so member lookups (keyed by Class#getName) can be exercised.
		String runtimeClass = NameServiceTest.class.getName();
		Map<String, String> runtime = Map.of(
				"fgt", runtimeClass,
				"fgt#t#Lfhx;", "field_1724",
				"fgt#u#[[I", "field_9999",
				"fgt#a#(Lfjz;)V", "method_1507",
				"fgt#a#()Z", "method_1493"
		);
		NameService service = new NameService(new NameService.RuntimeNames() {
			@Override
			public String mapClass(String obfClass) {
				return runtime.getOrDefault(obfClass, obfClass);
			}

			@Override
			public String mapField(String obfOwner, String obfName, String obfDescriptor) {
				return runtime.getOrDefault(obfOwner + "#" + obfName + "#" + obfDescriptor, obfName);
			}

			@Override
			public String mapMethod(String obfOwner, String obfName, String obfDescriptor) {
				return runtime.getOrDefault(obfOwner + "#" + obfName + "#" + obfDescriptor, obfName);
			}
		});
		service.loadFromReader(new BufferedReader(new StringReader(sampleMapping)));

		assertEquals("net.minecraft.client.Minecraft", service.resolveClassName(runtimeClass));
		assertEquals(runtimeClass, service.toRuntimeClassName("net.minecraft.client.Minecraft"));

		Class<?> owner = NameServiceTest.class;
		assertEquals("player", service.resolveFieldName(owner, "field_1724"));
		assertEquals("counters", service.resolveFieldName(owner, "field_9999"));
		assertEquals("setScreen", service.resolveMethodName(owner, "method_1507"));
		assertEquals("isPaused", service.resolveMethodName(owner, "method_1493"));
		assertEquals("method_1507", service.toRuntimeMethodName(owner, "setScreen"));
		assertEquals("field_1724", service.toRuntimeFieldName(owner, "player"));
		assertEquals("<init>", service.resolveMethodName(owner, "<init>"));
	}

	@Test
	void buildsObfuscatedDescriptors() {
		Map<String, String> classes = Map.of("net.minecraft.client.player.LocalPlayer", "fhx");
		assertEquals("Lfhx;", NameService.descriptor("net.minecraft.client.player.LocalPlayer", classes));
		assertEquals("[[I", NameService.descriptor("int[][]", classes));
		assertEquals("V", NameService.descriptor("void", classes));
		assertEquals("Ljava/util/List;", NameService.descriptor("java.util.List", classes));
		assertEquals("[Lfhx;", NameService.descriptor("net.minecraft.client.player.LocalPlayer[]", classes));
	}
}
