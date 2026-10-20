package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.http.ProblemException;
import dev.advik.mcdt.reflection.NameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VineflowerDecompilerTest {
	@TempDir
	Path tempDir;

	private VineflowerHost host;
	private DecompilerService service;
	private McdtConfig config;
	private NameService nameService;

	@BeforeEach
	void setUp() throws Exception {
		config = new McdtConfig();
		config.enableDecompiler = true;
		config.decompilerCacheSize = 10;
		nameService = new NameService();
		host = new VineflowerHost(tempDir);
		service = new DecompilerService(config, host, nameService);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (service != null) {
			service.close();
		}
	}

	@Test
	void testClassLoaderIsolation() {
		// Vineflower classes MUST NOT exist on the game or system classloader
		assertThrows(ClassNotFoundException.class, () ->
				Class.forName("org.jetbrains.java.decompiler.main.Fernflower", false, ClassLoader.getSystemClassLoader())
		);
		assertThrows(ClassNotFoundException.class, () ->
				Class.forName("org.jetbrains.java.decompiler.main.extern.IBytecodeProvider", false, Thread.currentThread().getContextClassLoader())
		);
		// The renamer class should also not exist on the system classloader
		assertThrows(ClassNotFoundException.class, () ->
				Class.forName("dev.advik.mcdt.decompiler.isolated.VineflowerRenamer", false, ClassLoader.getSystemClassLoader())
		);

		// But they must exist inside the isolated classloader
		assertNotNull(host.fernflowerClass());
		assertNotNull(host.bytecodeProviderClass());
		assertNotNull(host.resultSaverClass());
	}

	@Test
	void testDecompileClassSuccess() {
		var future = service.decompileAsync("dev.advik.mcdt.config.McdtConfig", true);
		var result = future.join();

		assertNotNull(result);
		assertEquals("dev.advik.mcdt.config.McdtConfig", result.className());
		assertFalse(result.cached());
		assertTrue(result.source().contains("public final class McdtConfig"));
		assertTrue(result.source().contains("public boolean enabled"));
	}

	@Test
	void testDecompileCache() {
		var first = service.decompileAsync("dev.advik.mcdt.config.McdtConfig", true).join();
		assertFalse(first.cached());

		var second = service.decompileAsync("dev.advik.mcdt.config.McdtConfig", true).join();
		assertTrue(second.cached());
		assertEquals(first.source(), second.source());
		assertEquals(0, second.durationMs());
	}

	@Test
	void testSecurityDenylistBlocksDangerousClasses() {
		assertThrows(ProblemException.class, () ->
				service.decompileAsync("java.lang.Runtime", true)
		);
		assertThrows(ProblemException.class, () ->
				service.decompileAsync("java.lang.ProcessBuilder", true)
		);
		assertThrows(ProblemException.class, () ->
				service.decompileAsync("java.io.File", true)
		);
		assertThrows(ProblemException.class, () ->
				service.decompileAsync("java.lang.ClassLoader", true)
		);
	}

	@Test
	void testDisabledDecompilerReturns503() {
		config.enableDecompiler = false;
		ProblemException ex = assertThrows(ProblemException.class, () ->
				service.decompileAsync("dev.advik.mcdt.config.McdtConfig", true)
		);
		assertEquals(503, ex.problem().status());
	}
}
