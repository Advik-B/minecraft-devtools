package dev.advik.mcdt.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BearerTokenTest {
	@TempDir
	Path tempDir;

	@Test
	void generatesValidBase64UrlToken() {
		BearerToken token = BearerToken.generate();
		assertNotNull(token.value());
		assertFalse(token.value().isBlank());
		assertTrue(token.matches(token.value()));
		assertFalse(token.matches("wrong-token"));
		assertFalse(token.matches(null));
	}

	@Test
	void redactsToString() {
		BearerToken token = BearerToken.generate();
		assertEquals("BearerToken[redacted]", token.toString());
		assertFalse(token.toString().contains(token.value()));
	}

	@Test
	void writesToOwnerOnlyFile() throws IOException {
		BearerToken token = BearerToken.generate();
		Path file = tempDir.resolve("token");
		token.writeTo(file);

		assertTrue(Files.exists(file));
		assertEquals(token.value(), Files.readString(file));
	}
}
