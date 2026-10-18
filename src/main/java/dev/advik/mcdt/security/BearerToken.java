package dev.advik.mcdt.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** The per-launch bearer token. */
public final class BearerToken {
	private static final SecureRandom RANDOM = new SecureRandom();

	private final String value;
	private final byte[] bytes;

	private BearerToken(String value) {
		this.value = value;
		this.bytes = value.getBytes(StandardCharsets.UTF_8);
	}

	/** A fresh random 256-bit token, base64url-encoded without padding. */
	public static BearerToken generate() {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		return new BearerToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
	}

	public static BearerToken of(String value) {
		return new BearerToken(value);
	}

	public String value() {
		return value;
	}

	/** Constant-time comparison against a presented token. */
	public boolean matches(String presented) {
		if (presented == null) {
			return false;
		}
		return MessageDigest.isEqual(bytes, presented.getBytes(StandardCharsets.UTF_8));
	}

	/** Writes the token to {@code file}, readable only by the current user. */
	public void writeTo(Path file) throws IOException {
		OwnerOnlyFiles.write(file, bytes);
	}

	@Override
	public String toString() {
		return "BearerToken[redacted]";
	}
}
