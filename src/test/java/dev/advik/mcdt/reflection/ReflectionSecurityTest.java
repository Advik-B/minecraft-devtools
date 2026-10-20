package dev.advik.mcdt.reflection;

import dev.advik.mcdt.http.ProblemException;
import dev.advik.mcdt.security.BearerToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionSecurityTest {

	static class CustomClassLoader extends ClassLoader {}

	static class DummyUserClass {
		public String userToken = "tok";
		public String userSecret = "sec";
		public String normal = "norm";
	}

	@Test
	void testBlockedClasses() {
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(Runtime.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(ProcessBuilder.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(Process.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(System.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(Thread.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(ThreadGroup.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(ClassLoader.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClass(CustomClassLoader.class));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClassName("java.lang.reflect.Method"));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClassName("java.lang.invoke.MethodHandles"));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClassName("sun.misc.Unsafe"));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClassName("java.io.File"));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClassName("java.nio.file.Files"));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClassName("java.net.Socket"));
		assertThrows(ProblemException.class, () -> ReflectionSecurity.checkClassName("java.security.SecureRandom"));
	}

	@Test
	void testAllowedAndNullClasses() {
		assertDoesNotThrow(() -> ReflectionSecurity.checkClass(null));
		assertDoesNotThrow(() -> ReflectionSecurity.checkClassName(null));
		assertDoesNotThrow(() -> ReflectionSecurity.checkClassName(""));
		assertDoesNotThrow(() -> ReflectionSecurity.checkClassName("   "));
		assertDoesNotThrow(() -> ReflectionSecurity.checkClass(String.class));
		assertDoesNotThrow(() -> ReflectionSecurity.checkClass(HandleTable.class));
		assertDoesNotThrow(() -> ReflectionSecurity.checkClassName("net.minecraft.client.Minecraft"));
		assertDoesNotThrow(() -> ReflectionSecurity.checkClassName("net.minecraft.client.player.LocalPlayer"));
	}

	@Test
	void testSensitiveFieldRedaction() {
		assertFalse(ReflectionSecurity.isSensitiveField(null, null));
		assertTrue(ReflectionSecurity.isSensitiveField(null, "accessToken"));
		assertTrue(ReflectionSecurity.isSensitiveField(null, "sessionToken"));
		assertTrue(ReflectionSecurity.isSensitiveField(null, "token"));
		assertTrue(ReflectionSecurity.isSensitiveField(null, "xuid"));
		assertTrue(ReflectionSecurity.isSensitiveField(null, "clientId"));
		assertTrue(ReflectionSecurity.isSensitiveField(null, "secretToken"));

		// Contextual check with declaring class
		assertTrue(ReflectionSecurity.isSensitiveField(DummyUserClass.class, "userToken"));
		assertTrue(ReflectionSecurity.isSensitiveField(DummyUserClass.class, "userSecret"));
		assertFalse(ReflectionSecurity.isSensitiveField(DummyUserClass.class, "normal"));

		assertFalse(ReflectionSecurity.isSensitiveField(null, "name"));
		assertFalse(ReflectionSecurity.isSensitiveField(null, "fps"));
		assertFalse(ReflectionSecurity.isSensitiveField(null, "renderDistance"));
	}

	@Test
	void testSensitiveObject() {
		assertFalse(ReflectionSecurity.isSensitiveObject(null));
		assertFalse(ReflectionSecurity.isSensitiveObject("plain string"));
		assertTrue(ReflectionSecurity.isSensitiveObject(BearerToken.of("secret-token")));
	}

	@Test
	void testSensitiveMethodRedaction() {
		assertFalse(ReflectionSecurity.isSensitiveMethod(null, null));
		assertTrue(ReflectionSecurity.isSensitiveMethod(null, "getAccessToken"));
		assertTrue(ReflectionSecurity.isSensitiveMethod(null, "getSessionId"));
		assertTrue(ReflectionSecurity.isSensitiveMethod(null, "getXuid"));
		assertTrue(ReflectionSecurity.isSensitiveMethod(null, "getClientId"));
		assertFalse(ReflectionSecurity.isSensitiveMethod(null, "getName"));
		assertFalse(ReflectionSecurity.isSensitiveMethod(null, "getFps"));
	}

	@Test
	void testSessionClassIsIdentifiedByTypeAndFailsClosed() {
		// In production the User class and its members have intermediary names, so unknown members are hidden.
		Class<?> user = net.minecraft.client.User.class;
		assertTrue(ReflectionSecurity.isSessionClass(user));
		assertTrue(ReflectionSecurity.isSensitiveField(user, "field_1982"));
		assertTrue(ReflectionSecurity.isSensitiveField(user, "uuid"));
		assertFalse(ReflectionSecurity.isSensitiveField(user, "name"));
		assertFalse(ReflectionSecurity.isSensitiveField(user, "type"));
		assertTrue(ReflectionSecurity.isSensitiveMethod(user, "method_1674"));
		assertTrue(ReflectionSecurity.isSensitiveMethod(user, "getProfileId"));
		assertFalse(ReflectionSecurity.isSensitiveMethod(user, "getName"));
		assertFalse(ReflectionSecurity.isSessionClass(String.class));
	}
}
