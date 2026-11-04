package dev.advik.mcdt.security;

import dev.advik.mcdt.http.ProblemException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmManagerTest {

	@Test
	void testCreateAndConsumeValidToken() {
		ConfirmManager manager = new ConfirmManager();
		ConfirmManager.TokenInfo info = manager.createToken("bearer-123", "quit");

		assertNotNull(info.token());
		assertNotNull(info.expiresAt());

		assertDoesNotThrow(() -> manager.validateAndConsume(info.token(), "bearer-123", "quit"));

		// Second consume fails (single-use)
		ProblemException e = assertThrows(ProblemException.class, () ->
				manager.validateAndConsume(info.token(), "bearer-123", "quit")
		);
		assertEquals(409, e.problem().status());
	}

	@Test
	void testInvalidTokenInputs() {
		ConfirmManager manager = new ConfirmManager();

		assertThrows(ProblemException.class, () -> manager.validateAndConsume(null, "bearer-123", "quit"));
		assertThrows(ProblemException.class, () -> manager.validateAndConsume("", "bearer-123", "quit"));
		assertThrows(ProblemException.class, () -> manager.validateAndConsume("non-existent", "bearer-123", "quit"));
	}

	@Test
	void testMismatchedBearerOrAction() {
		ConfirmManager manager = new ConfirmManager();
		ConfirmManager.TokenInfo info = manager.createToken("bearer-123", "quit");

		// Wrong bearer
		assertThrows(ProblemException.class, () -> manager.validateAndConsume(info.token(), "wrong-bearer", "quit"));

		// Wrong action
		ConfirmManager.TokenInfo info2 = manager.createToken("bearer-123", "quit");
		assertThrows(ProblemException.class, () -> manager.validateAndConsume(info2.token(), "bearer-123", "other-action"));
	}

	@Test
	void testExpiredToken() throws Exception {
		// Manager with negative duration (already expired)
		ConfirmManager manager = new ConfirmManager(Duration.ofMillis(-100));
		ConfirmManager.TokenInfo info = manager.createToken("bearer-123", "quit");

		assertThrows(ProblemException.class, () -> manager.validateAndConsume(info.token(), "bearer-123", "quit"));
	}
}
