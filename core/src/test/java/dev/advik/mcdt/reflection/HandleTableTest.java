package dev.advik.mcdt.reflection;

import dev.advik.mcdt.http.ProblemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandleTableTest {
	private HandleTable handleTable;

	@BeforeEach
	void setUp() {
		handleTable = new HandleTable(5); // Small capacity for testing LRU
	}

	@Test
	void testHandleCreationAndRetrieval() {
		assertNull(handleTable.getOrCreateHandle(null));

		Object obj = new Object();
		String handle = handleTable.getOrCreateHandle(obj);

		assertNotNull(handle);
		assertTrue(handle.startsWith("h_"));
		assertEquals(10, handle.length()); // "h_" + 8 hex chars

		Object retrieved = handleTable.get(handle);
		assertSame(obj, retrieved);

		// Reuses handle for identical object
		String secondHandle = handleTable.getOrCreateHandle(obj);
		assertEquals(handle, secondHandle);
	}

	@Test
	void testHandleRelease() {
		assertFalse(handleTable.release(null));
		assertFalse(handleTable.release(""));
		assertFalse(handleTable.release("minecraft"));

		Object obj = new Object();
		String handle = handleTable.getOrCreateHandle(obj);

		assertTrue(handleTable.release(handle));
		assertFalse(handleTable.release(handle)); // Already released

		ProblemException e = assertThrows(ProblemException.class, () -> handleTable.get(handle));
		assertEquals(404, e.problem().status());
	}

	@Test
	void testRootHandlesProtection() {
		assertTrue(HandleTable.isRoot("minecraft"));
		assertTrue(HandleTable.isRoot("player"));
		assertTrue(HandleTable.isRoot("level"));
		assertTrue(HandleTable.isRoot("screen"));
		assertTrue(HandleTable.isRoot("options"));
		assertTrue(HandleTable.isRoot("gameRenderer"));
		assertFalse(HandleTable.isRoot(null));
		assertFalse(HandleTable.isRoot("other"));

		assertEquals(6, HandleTable.getRootNames().size());
		assertNotNull(HandleTable.getInstance());
	}

	@Test
	void testLruEviction() {
		HandleTable lruTable = new HandleTable(3);
		Object obj1 = new Object();
		Object obj2 = new Object();
		Object obj3 = new Object();
		Object obj4 = new Object();

		String h1 = lruTable.getOrCreateHandle(obj1);
		String h2 = lruTable.getOrCreateHandle(obj2);
		String h3 = lruTable.getOrCreateHandle(obj3);

		// Access h1 so h2 becomes the eldest
		lruTable.get(h1);

		// Insert 4th -> h2 should be evicted
		String h4 = lruTable.getOrCreateHandle(obj4);

		assertNotNull(lruTable.get(h1));
		assertNotNull(lruTable.get(h3));
		assertNotNull(lruTable.get(h4));

		ProblemException e = assertThrows(ProblemException.class, () -> lruTable.get(h2));
		assertEquals(404, e.problem().status());
	}

	@Test
	void testClear() {
		Object obj1 = new Object();
		Object obj2 = new Object();
		String h1 = handleTable.getOrCreateHandle(obj1);
		String h2 = handleTable.getOrCreateHandle(obj2);

		assertEquals(2, handleTable.size());
		handleTable.clear();
		assertEquals(0, handleTable.size());

		assertThrows(ProblemException.class, () -> handleTable.get(h1));
		assertThrows(ProblemException.class, () -> handleTable.get(h2));
	}

	@Test
	void testInvalidAndMissingHandleGet() {
		ProblemException e1 = assertThrows(ProblemException.class, () -> handleTable.get(null));
		assertEquals(404, e1.problem().status());

		ProblemException e2 = assertThrows(ProblemException.class, () -> handleTable.get("   "));
		assertEquals(404, e2.problem().status());

		ProblemException e3 = assertThrows(ProblemException.class, () -> handleTable.get("h_00000000"));
		assertEquals(404, e3.problem().status());
	}

	@Test
	@SuppressWarnings("unchecked")
	void testTtlExpiration() throws Exception {
		Object obj = new Object();
		String handle = handleTable.getOrCreateHandle(obj);

		Field entriesField = HandleTable.class.getDeclaredField("entries");
		entriesField.setAccessible(true);
		Map<String, Object> entries = (Map<String, Object>) entriesField.get(handleTable);
		Object entry = entries.get(handle);

		// Backdate lastAccess beyond 10 minutes (EXPIRY_MS = 600,000)
		Field lastAccessField = entry.getClass().getDeclaredField("lastAccess");
		lastAccessField.setAccessible(true);
		lastAccessField.set(entry, System.currentTimeMillis() - 700_000L);

		ProblemException e = assertThrows(ProblemException.class, () -> handleTable.get(handle));
		assertEquals(404, e.problem().status());
	}

	@Test
	@SuppressWarnings("unchecked")
	void testGarbageCollectedTargetReturns410Gone() throws Exception {
		Object obj = new Object();
		String handle = handleTable.getOrCreateHandle(obj);

		Field entriesField = HandleTable.class.getDeclaredField("entries");
		entriesField.setAccessible(true);
		Map<String, Object> entries = (Map<String, Object>) entriesField.get(handleTable);
		Object entry = entries.get(handle);

		// Clear the WeakReference target
		Field refField = entry.getClass().getDeclaredField("ref");
		refField.setAccessible(true);
		java.lang.ref.WeakReference<?> ref = (java.lang.ref.WeakReference<?>) refField.get(entry);
		ref.clear();

		ProblemException e = assertThrows(ProblemException.class, () -> handleTable.get(handle));
		assertEquals(410, e.problem().status());
		assertEquals("handle-gone", e.problem().type());
	}
}
