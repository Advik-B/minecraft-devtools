package dev.advik.mcdt.reflection;

import dev.advik.mcdt.http.Problems;

import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.*;

/**
 * Manages weak-reference handles to live game objects.
 * <p>
 * Handles expire after 10 minutes of inactivity, return 410 Gone if their target object is
 * garbage-collected, and are evicted via LRU when the table reaches 50,000 entries.
 */
public final class HandleTable {
	public static final int DEFAULT_MAX_CAPACITY = 50_000;
	public static final long EXPIRY_MS = 10 * 60 * 1000L; // 10 minutes

	private static final Set<String> ROOT_HANDLES = Set.of(
			"minecraft", "player", "level", "screen", "options", "gameRenderer"
	);

	private static final HandleTable INSTANCE = new HandleTable();

	public static HandleTable getInstance() {
		return INSTANCE;
	}

	private static final class HandleEntry {
		final String handle;
		final WeakReference<Object> ref;
		final long createdAt;
		long lastAccess;

		HandleEntry(String handle, Object target, long now) {
			this.handle = handle;
			this.ref = new WeakReference<>(target);
			this.createdAt = now;
			this.lastAccess = now;
		}
	}

	private final int maxCapacity;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, HandleEntry> entries;
	private final WeakHashMap<Object, String> reverseMap = new WeakHashMap<>();

	public HandleTable() {
		this(DEFAULT_MAX_CAPACITY);
	}

	public HandleTable(int maxCapacity) {
		this.maxCapacity = maxCapacity;
		this.entries = new LinkedHashMap<>(128, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, HandleEntry> eldest) {
				return size() > maxCapacity;
			}
		};
	}

	public static boolean isRoot(String handle) {
		return handle != null && ROOT_HANDLES.contains(handle);
	}

	public static Set<String> getRootNames() {
		return ROOT_HANDLES;
	}

	/**
	 * Registers an object in the table, returning its handle ID (e.g. {@code h_8f3a1b2c}).
	 * Reuses existing handles if the object is already registered and still valid.
	 */
	public synchronized String getOrCreateHandle(Object target) {
		if (target == null) {
			return null;
		}

		String existingHandle = reverseMap.get(target);
		if (existingHandle != null) {
			HandleEntry entry = entries.get(existingHandle);
			if (entry != null && System.currentTimeMillis() - entry.lastAccess <= EXPIRY_MS) {
				entry.lastAccess = System.currentTimeMillis();
				return existingHandle;
			}
		}

		String handle = generateHandleId();
		long now = System.currentTimeMillis();
		HandleEntry entry = new HandleEntry(handle, target, now);
		entries.put(handle, entry);
		reverseMap.put(target, handle);
		return handle;
	}

	/**
	 * Retrieves the object corresponding to the handle.
	 *
	 * @throws dev.advik.mcdt.http.ProblemException 410 Gone if collected, 404 Not Found if missing/expired.
	 */
	public synchronized Object get(String handle) {
		if (handle == null || handle.isBlank()) {
			throw Problems.handleNotFound(String.valueOf(handle));
		}

		HandleEntry entry = entries.get(handle);
		if (entry == null) {
			throw Problems.handleNotFound(handle);
		}

		long now = System.currentTimeMillis();
		if (now - entry.lastAccess > EXPIRY_MS) {
			entries.remove(handle);
			throw Problems.handleNotFound(handle);
		}

		Object target = entry.ref.get();
		if (target == null) {
			entries.remove(handle);
			throw Problems.handleGone(handle);
		}

		entry.lastAccess = now;
		return target;
	}

	/**
	 * Releases a handle early.
	 */
	public synchronized boolean release(String handle) {
		if (handle == null || isRoot(handle)) {
			return false;
		}
		HandleEntry removed = entries.remove(handle);
		if (removed != null) {
			Object target = removed.ref.get();
			if (target != null) {
				reverseMap.remove(target);
			}
			return true;
		}
		return false;
	}

	/**
	 * Clears all handles (e.g. on world leave).
	 */
	public synchronized void clear() {
		entries.clear();
		reverseMap.clear();
	}

	public synchronized int size() {
		return entries.size();
	}

	private String generateHandleId() {
		byte[] bytes = new byte[4];
		String id;
		do {
			random.nextBytes(bytes);
			id = "h_" + String.format("%02x%02x%02x%02x", bytes[0], bytes[1], bytes[2], bytes[3]);
		} while (entries.containsKey(id));
		return id;
	}
}
