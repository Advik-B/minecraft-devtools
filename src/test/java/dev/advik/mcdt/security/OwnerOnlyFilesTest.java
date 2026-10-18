package dev.advik.mcdt.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OwnerOnlyFilesTest {

	@Test
	void testWriteCreatesFileWithContent(@TempDir Path tempDir) throws Exception {
		Path target = tempDir.resolve("nested").resolve("secret.txt");
		byte[] content = "my-secret-content".getBytes(StandardCharsets.UTF_8);

		OwnerOnlyFiles.write(target, content);

		assertTrue(Files.exists(target));
		assertArrayEquals(content, Files.readAllBytes(target));

		// Overwrite existing file atomically
		byte[] newContent = "updated-secret".getBytes(StandardCharsets.UTF_8);
		OwnerOnlyFiles.write(target, newContent);

		assertArrayEquals(newContent, Files.readAllBytes(target));

		Files.deleteIfExists(target);
		Files.deleteIfExists(target.getParent());
	}
}
