package dev.advik.mcdt.reflection;

import dev.advik.mcdt.security.BearerToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionSerializerTest {
	private HandleTable handleTable;
	private NameService nameService;
	private ReflectionCache cache;
	private ReflectionSerializer serializer;

	enum SampleEnum {
		ALPHA,
		BETA
	}

	static class SampleCycle {
		public String name = "cycle-root";
		public SampleCycle self;
	}

	static class SampleSensitive {
		public String accessToken = "secret-12345";
		public String token = "secret-token";
		public String normal = "visible-value";
	}

	static class SampleStaticClass {
		public static final String STATIC_NAME = "MCDT_STATIC";
		public static final String accessToken = "sensitive_static";
		public static int getMeaning() { return 42; }
	}

	static class SampleComplex {
		public int number = 42;
		public double ratio = 3.14;
		public boolean flag = true;
		public char ch = 'Z';
		public String text = "hello";
		public SampleEnum enumVal = SampleEnum.ALPHA;
		public UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
		public List<String> list = List.of("one", "two");
		public int[] numbers = new int[]{10, 20, 30};
		public Map<String, Object> map = Map.of("key1", "val1", "key2", 123);
	}

	@BeforeEach
	void setUp() {
		handleTable = new HandleTable();
		nameService = new NameService();
		cache = new ReflectionCache(nameService);
		serializer = new ReflectionSerializer(handleTable, nameService, cache);
	}

	@Test
	void testGetters() {
		assertSame(handleTable, serializer.handleTable());
		assertSame(nameService, serializer.nameService());
		assertSame(cache, serializer.cache());
	}

	@Test
	void testSerializeNullAndPrimitives() {
		assertNull(serializer.serialize(null, 0, 1, new HashSet<>(), null, null));
		assertEquals(123, serializer.serialize(123, 0, 1, new HashSet<>(), null, null));
		assertEquals(3.14, serializer.serialize(3.14, 0, 1, new HashSet<>(), null, null));
		assertEquals(true, serializer.serialize(true, 0, 1, new HashSet<>(), null, null));
		assertEquals('A', serializer.serialize('A', 0, 1, new HashSet<>(), null, null));
		assertEquals("test", serializer.serialize("test", 0, 1, new HashSet<>(), null, null));
		assertEquals("ALPHA", serializer.serialize(SampleEnum.ALPHA, 0, 1, new HashSet<>(), null, null));
		UUID u = UUID.randomUUID();
		assertEquals(u.toString(), serializer.serialize(u, 0, 1, new HashSet<>(), null, null));
	}

	@Test
	void testSensitiveObjectRedaction() {
		BearerToken token = BearerToken.of("my-secret-token");
		Object serialized = serializer.serialize(token, 0, 1, new HashSet<>(), null, null);
		assertEquals(ReflectionSecurity.REDACTED, serialized);
	}

	@Test
	void testMinecraftTypeAdapters() {
		Vec3 vec = new Vec3(1.5, 64.0, -10.5);
		Object vecObj = serializer.serialize(vec, 0, 1, new HashSet<>(), null, null);
		assertTrue(vecObj instanceof Map<?, ?>);
		Map<?, ?> vecMap = (Map<?, ?>) vecObj;
		assertEquals(1.5, vecMap.get("x"));
		assertEquals(64.0, vecMap.get("y"));
		assertEquals(-10.5, vecMap.get("z"));

		Vec3i pos = new BlockPos(10, 20, 30);
		Object posObj = serializer.serialize(pos, 0, 1, new HashSet<>(), null, null);
		assertTrue(posObj instanceof Map<?, ?>);
		Map<?, ?> posMap = (Map<?, ?>) posObj;
		assertEquals(10, posMap.get("x"));
		assertEquals(20, posMap.get("y"));
		assertEquals(30, posMap.get("z"));

		ResourceLocation res = ResourceLocation.parse("minecraft:diamond_sword");
		assertEquals("minecraft:diamond_sword", serializer.serialize(res, 0, 1, new HashSet<>(), null, null));

		Component comp = Component.literal("Hello Minecraft");
		Object compObj = serializer.serialize(comp, 0, 1, new HashSet<>(), null, null);
		assertTrue(compObj instanceof Map<?, ?>);
		Map<?, ?> compMap = (Map<?, ?>) compObj;
		assertTrue(compMap.containsKey("text"));
	}

	@Test
	void testSerializeCollectionsAndPagination() {
		List<String> items = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			items.add("item-" + i);
		}

		// Depth limit hit
		Object handleDesc = serializer.serialize(items, 1, 1, new HashSet<>(), null, null);
		assertTrue(handleDesc instanceof Map<?, ?>);
		Map<?, ?> handleMap = (Map<?, ?>) handleDesc;
		assertTrue(handleMap.containsKey("$handle"));
		assertEquals(100, handleMap.get("size"));

		// Depth not hit: default pagination (limit 64)
		Object expanded = serializer.serialize(items, 0, 1, new HashSet<>(), 0, 10);
		assertTrue(expanded instanceof Map<?, ?>);
		Map<?, ?> expandedMap = (Map<?, ?>) expanded;
		assertEquals("collection", expandedMap.get("$type"));
		assertEquals(100, expandedMap.get("size"));
		assertTrue((Boolean) expandedMap.get("truncated"));
		List<?> returnedItems = (List<?>) expandedMap.get("items");
		assertEquals(10, returnedItems.size());
		assertEquals("item-0", returnedItems.get(0));

		// Pagination with offset
		Object paged = serializer.serialize(items, 0, 1, new HashSet<>(), 10, 5);
		Map<?, ?> pagedMap = (Map<?, ?>) paged;
		List<?> pagedItems = (List<?>) pagedMap.get("items");
		assertEquals(5, pagedItems.size());
		assertEquals("item-10", pagedItems.get(0));
	}

	@Test
	void testSerializeArraysAndPagination() {
		String[] array = new String[80];
		for (int i = 0; i < 80; i++) {
			array[i] = "arr-" + i;
		}

		// Depth limit hit
		Object handleDesc = serializer.serialize(array, 1, 1, new HashSet<>(), null, null);
		Map<?, ?> handleMap = (Map<?, ?>) handleDesc;
		assertTrue(handleMap.containsKey("$handle"));
		assertEquals(80, handleMap.get("size"));

		// Depth not hit: array expansion
		Object expanded = serializer.serialize(array, 0, 1, new HashSet<>(), 5, 10);
		Map<?, ?> expandedMap = (Map<?, ?>) expanded;
		assertEquals("array", expandedMap.get("$type"));
		assertEquals(80, expandedMap.get("size"));
		assertTrue((Boolean) expandedMap.get("truncated"));
		List<?> returnedItems = (List<?>) expandedMap.get("items");
		assertEquals(10, returnedItems.size());
		assertEquals("arr-5", returnedItems.get(0));
	}

	@Test
	void testSerializeMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < 70; i++) {
			map.put("k" + i, "v" + i);
		}

		// Depth limit hit
		Object handleDesc = serializer.serialize(map, 1, 1, new HashSet<>(), null, null);
		Map<?, ?> handleMap = (Map<?, ?>) handleDesc;
		assertTrue(handleMap.containsKey("$handle"));
		assertEquals(70, handleMap.get("size"));

		// Depth not hit: map expansion capped at 64
		Object expanded = serializer.serialize(map, 0, 1, new HashSet<>(), null, null);
		Map<?, ?> expandedMap = (Map<?, ?>) expanded;
		assertEquals(64, expandedMap.size());
	}

	@Test
	void testCycleDetection() {
		SampleCycle node1 = new SampleCycle();
		SampleCycle node2 = new SampleCycle();
		node1.self = node2;
		node2.self = node1;

		Object serialized = serializer.serialize(node1, 0, 3, new HashSet<>(), null, null);
		assertTrue(serialized instanceof Map<?, ?>);
		Map<?, ?> map = (Map<?, ?>) serialized;
		assertTrue(map.containsKey("fields"));
	}

	@Test
	void testSensitiveFieldRedactionInObject() {
		SampleSensitive obj = new SampleSensitive();
		Object serialized = serializer.serialize(obj, 0, 1, new HashSet<>(), null, null);
		assertTrue(serialized instanceof Map<?, ?>);
		Map<?, ?> map = (Map<?, ?>) serialized;
		Map<?, ?> fields = (Map<?, ?>) map.get("fields");
		assertEquals(ReflectionSecurity.REDACTED, fields.get("accessToken"));
		assertEquals(ReflectionSecurity.REDACTED, fields.get("token"));
		assertEquals("visible-value", fields.get("normal"));
	}

	@Test
	void testInspectObject() {
		// Null object
		Map<String, Object> nullInspect = serializer.inspectObject(null, "null-handle", 1, null, null);
		assertEquals("null-handle", nullInspect.get("$handle"));
		assertEquals("null", nullInspect.get("$class"));

		// Collection object
		List<String> list = List.of("x", "y");
		Map<String, Object> listInspect = serializer.inspectObject(list, "h_list", 1, 0, 10);
		assertEquals("h_list", listInspect.get("$handle"));
		assertEquals(2, listInspect.get("size"));
		assertTrue(listInspect.containsKey("items"));

		// Array object
		int[] arr = new int[]{1, 2, 3};
		Map<String, Object> arrInspect = serializer.inspectObject(arr, "h_arr", 1, 0, 10);
		assertEquals("h_arr", arrInspect.get("$handle"));
		assertEquals(3, arrInspect.get("size"));
		assertTrue(arrInspect.containsKey("items"));

		// Normal complex object
		SampleComplex complex = new SampleComplex();
		Map<String, Object> inspect = serializer.inspectObject(complex, "h_comp", 1, null, null);
		assertEquals("h_comp", inspect.get("$handle"));
		assertTrue(inspect.containsKey("fields"));
		assertTrue(inspect.containsKey("methods"));
	}

	@Test
	void testInspectClass() {
		Map<String, Object> classInspect = serializer.inspectClass(SampleStaticClass.class, 1);
		assertEquals(SampleStaticClass.class.getName(), classInspect.get("$class"));
		assertTrue(classInspect.containsKey("staticFields"));
		assertTrue(classInspect.containsKey("staticMethods"));

		Map<?, ?> staticFields = (Map<?, ?>) classInspect.get("staticFields");
		assertEquals("MCDT_STATIC", staticFields.get("STATIC_NAME"));
		assertEquals(ReflectionSecurity.REDACTED, staticFields.get("accessToken"));

		List<?> staticMethods = (List<?>) classInspect.get("staticMethods");
		assertFalse(staticMethods.isEmpty());
	}
}
