package dev.advik.mcdt.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PermissionTest {

	@Test
	void testPermissionGrantHierarchy() {
		assertTrue(Permission.READ.grants(Permission.READ));
		assertFalse(Permission.READ.grants(Permission.ACT));
		assertFalse(Permission.READ.grants(Permission.REFLECT_WRITE));

		assertTrue(Permission.ACT.grants(Permission.READ));
		assertTrue(Permission.ACT.grants(Permission.ACT));
		assertFalse(Permission.ACT.grants(Permission.REFLECT_WRITE));

		assertTrue(Permission.REFLECT_WRITE.grants(Permission.READ));
		assertTrue(Permission.REFLECT_WRITE.grants(Permission.ACT));
		assertTrue(Permission.REFLECT_WRITE.grants(Permission.REFLECT_WRITE));
	}

	@Test
	void testFromIdNormalization() {
		assertEquals(Permission.READ, Permission.fromId("read"));
		assertEquals(Permission.READ, Permission.fromId("READ"));
		assertEquals(Permission.ACT, Permission.fromId("act"));
		assertEquals(Permission.REFLECT_WRITE, Permission.fromId("reflect-write"));
		assertEquals(Permission.REFLECT_WRITE, Permission.fromId("REFLECT_WRITE"));

		assertThrows(IllegalArgumentException.class, () -> Permission.fromId("unknown-permission"));
	}
}
