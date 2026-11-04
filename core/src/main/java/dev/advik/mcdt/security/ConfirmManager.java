package dev.advik.mcdt.security;

import dev.advik.mcdt.http.Problems;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages short-lived confirmation tokens for irreversible or destructive operations.
 */
public final class ConfirmManager {
	private static final Duration DEFAULT_EXPIRY = Duration.ofSeconds(30);

	private record Entry(String bearerToken, String action, Instant expiresAt) {
		boolean isValid(String bearerToken, String action, Instant now) {
			return this.bearerToken.equals(bearerToken) && this.action.equals(action) && now.isBefore(expiresAt);
		}
	}

	private final Map<String, Entry> activeTokens = new ConcurrentHashMap<>();
	private final Duration expiry;

	public ConfirmManager() {
		this(DEFAULT_EXPIRY);
	}

	public ConfirmManager(Duration expiry) {
		this.expiry = expiry;
	}

	public record TokenInfo(String token, Instant expiresAt) {}

	public TokenInfo createToken(String bearerToken, String action) {
		cleanup();
		String token = UUID.randomUUID().toString();
		Instant expiresAt = Instant.now().plus(expiry);
		activeTokens.put(token, new Entry(bearerToken, action, expiresAt));
		return new TokenInfo(token, expiresAt);
	}

	public void validateAndConsume(String token, String bearerToken, String action) {
		cleanup();
		if (token == null || token.isBlank()) {
			throw Problems.quitTokenInvalid();
		}
		Entry entry = activeTokens.remove(token);
		if (entry == null || !entry.isValid(bearerToken, action, Instant.now())) {
			throw Problems.quitTokenInvalid();
		}
	}

	private void cleanup() {
		Instant now = Instant.now();
		activeTokens.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
	}
}
