package dev.advik.mcdt.reflection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReflectionCacheTest {
	private NameService nameService;
	private ReflectionCache cache;

	static class SampleBase {
		public static String staticBaseField = "baseStatic";
		public String baseField = "baseInstance";

		public static String getStaticBase() {
			return staticBaseField;
		}

		public String getBaseField() {
			return baseField;
		}
	}

	static class SampleChild extends SampleBase {
		public static int staticNumber = 999;
		private final String childField = "childVal";

		public static int add(int a, int b) {
			return a + b;
		}

		public String getChildField() {
			return childField;
		}

		public String echo(String input) {
			return "echo: " + input;
		}
	}

	@BeforeEach
	void setUp() {
		nameService = new NameService();
		cache = new ReflectionCache(nameService);
	}

	@Test
	void testCacheMetadataReuse() {
		ReflectionCache.ClassMetadata meta1 = cache.getMetadata(SampleChild.class);
		ReflectionCache.ClassMetadata meta2 = cache.getMetadata(SampleChild.class);
		assertSame(meta1, meta2);

		assertEquals(SampleChild.class, meta1.targetClass());
		assertEquals(SampleChild.class.getName(), meta1.resolvedClassName());
	}

	@Test
	void testFieldMetadataAndGetters() throws Throwable {
		ReflectionCache.ClassMetadata meta = cache.getMetadata(SampleChild.class);
		ReflectionCache.FieldMetadata staticField = meta.getField("staticNumber");
		assertNotNull(staticField);
		assertTrue(staticField.isStatic());
		assertEquals("int", staticField.typeName());
		assertEquals("staticNumber", staticField.resolvedName());
		assertEquals("staticNumber", staticField.rawName());
		assertEquals(999, staticField.getValue(null));

		ReflectionCache.FieldMetadata childField = meta.getField("childField");
		assertNotNull(childField);
		assertFalse(childField.isStatic());
		assertTrue(childField.isFinal());
		assertEquals("java.lang.String", childField.typeName());

		SampleChild instance = new SampleChild();
		assertEquals("childVal", childField.getValue(instance));

		// Inherited field
		ReflectionCache.FieldMetadata baseField = meta.getField("baseField");
		assertNotNull(baseField);
		assertEquals("baseInstance", baseField.getValue(instance));
	}

	@Test
	void testMethodMetadataAndInvoke() throws Throwable {
		ReflectionCache.ClassMetadata meta = cache.getMetadata(SampleChild.class);

		// Static method invocation
		List<ReflectionCache.MethodMetadata> addMethods = meta.getMethods("add");
		assertNotNull(addMethods);
		assertFalse(addMethods.isEmpty());
		ReflectionCache.MethodMetadata addMethod = addMethods.get(0);
		assertTrue(addMethod.isStatic());
		assertEquals("int", addMethod.returnType());
		assertEquals(List.of("int", "int"), addMethod.parameterTypes());
		assertEquals(15, addMethod.invoke(null, 7, 8));

		// Instance method invocation
		ReflectionCache.MethodMetadata echoMethod = meta.getMethods("echo").get(0);
		assertFalse(echoMethod.isStatic());
		SampleChild instance = new SampleChild();
		assertEquals("echo: hello", echoMethod.invoke(instance, "hello"));

		// Zero-arg method lookup
		ReflectionCache.MethodMetadata zeroArg = meta.getZeroArgMethod("getChildField");
		assertNotNull(zeroArg);
		assertEquals("childVal", zeroArg.invoke(instance));

		ReflectionCache.MethodMetadata inheritedZeroArg = meta.getZeroArgMethod("getBaseField");
		assertNotNull(inheritedZeroArg);
		assertEquals("baseInstance", inheritedZeroArg.invoke(instance));

		assertNull(meta.getZeroArgMethod("nonExistent"));
		assertNull(meta.getField("nonExistent"));
	}

	@Test
	void testFallbackFieldAndMethodInvocationWithoutHandle() throws Throwable {
		ReflectionCache.ClassMetadata meta = cache.getMetadata(SampleChild.class);
		ReflectionCache.FieldMetadata childField = meta.getField("childField");

		// Construct FieldMetadata without MethodHandle to exercise fallback field.get(instance)
		ReflectionCache.FieldMetadata fallbackField = new ReflectionCache.FieldMetadata(
				childField.field(),
				childField.resolvedName(),
				childField.rawName(),
				childField.typeName(),
				childField.isStatic(),
				childField.isFinal(),
				null
		);
		SampleChild instance = new SampleChild();
		assertEquals("childVal", fallbackField.getValue(instance));

		// Construct MethodMetadata without MethodHandle to exercise fallback method.invoke(instance, args)
		ReflectionCache.MethodMetadata echoMethod = meta.getMethods("echo").get(0);
		ReflectionCache.MethodMetadata fallbackMethod = new ReflectionCache.MethodMetadata(
				echoMethod.method(),
				echoMethod.resolvedName(),
				echoMethod.rawName(),
				echoMethod.returnType(),
				echoMethod.parameterTypes(),
				echoMethod.isStatic(),
				null
		);
		assertEquals("echo: fallback", fallbackMethod.invoke(instance, "fallback"));

		// Static method fallback
		ReflectionCache.MethodMetadata addMethod = meta.getMethods("add").get(0);
		ReflectionCache.MethodMetadata fallbackStaticMethod = new ReflectionCache.MethodMetadata(
				addMethod.method(),
				addMethod.resolvedName(),
				addMethod.rawName(),
				addMethod.returnType(),
				addMethod.parameterTypes(),
				addMethod.isStatic(),
				null
		);
		assertEquals(10, fallbackStaticMethod.invoke(null, 4, 6));
	}
}
