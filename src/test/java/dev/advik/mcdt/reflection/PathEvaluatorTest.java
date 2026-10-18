package dev.advik.mcdt.reflection;

import dev.advik.mcdt.http.ProblemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathEvaluatorTest {
	private PathEvaluator evaluator;

	static class SampleItem {
		public final String id;
		SampleItem(String id) { this.id = id; }
	}

	static class SampleInventory {
		public final List<SampleItem> items = new ArrayList<>(List.of(
				new SampleItem("minecraft:diamond_sword"),
				new SampleItem("minecraft:shield")
		));
		public final String[] tags = new String[]{"sharpness", "unbreaking"};
		public final Set<String> categories = new LinkedHashSet<>(List.of("weapon", "melee"));
		public SampleItem nullItem = null;

		public List<SampleItem> getItems() { return items; }
		public int size() { return items.size(); }
		public boolean isEquipped() { return true; }
		public int rawCount() { return 64; }
	}

	static class SamplePlayer {
		public final String name = "Steve";
		private final SampleInventory inventory = new SampleInventory();
		public SampleInventory getInventory() { return inventory; }
	}

	static class SampleRoot {
		public final SamplePlayer player = new SamplePlayer();
		public final Map<String, Object> tags = Map.of("version", 1, "custom", "test");
		public SamplePlayer getPlayer() { return player; }
	}

	@BeforeEach
	void setUp() {
		NameService nameService = new NameService();
		ReflectionCache cache = new ReflectionCache(nameService);
		evaluator = new PathEvaluator(cache);
	}

	@Test
	void testNullAndEmptyPathReturnsRoot() {
		SampleRoot root = new SampleRoot();
		assertSame(root, evaluator.evaluate(root, null));
		assertSame(root, evaluator.evaluate(root, ""));
		assertSame(root, evaluator.evaluate(root, "   "));
	}

	@Test
	void testNullRootThrows() {
		ProblemException e = assertThrows(ProblemException.class, () -> evaluator.evaluate(null, "foo"));
		assertEquals(400, e.problem().status());
		assertTrue(e.problem().detail().contains("Cannot traverse path on null root"));
	}

	@Test
	void testParsePathSegments() {
		List<String> segments = PathEvaluator.parsePathSegments("player.getInventory().items[0]");
		assertEquals(List.of("player", "getInventory()", "items", "[0]"), segments);

		List<String> mapSegments = PathEvaluator.parsePathSegments("tags[\"version\"]");
		assertEquals(List.of("tags", "[\"version\"]"), mapSegments);

		List<String> singleQuoteSegments = PathEvaluator.parsePathSegments("tags['version']");
		assertEquals(List.of("tags", "['version']"), singleQuoteSegments);
	}

	@Test
	void testDirectFieldAccess() {
		SampleRoot root = new SampleRoot();
		Object result = evaluator.evaluate(root, "player.name");
		assertEquals("Steve", result);
	}

	@Test
	void testZeroArgMethodCall() {
		SampleRoot root = new SampleRoot();
		Object result = evaluator.evaluate(root, "player.getInventory().size()");
		assertEquals(2, result);
	}

	@Test
	void testGetterFallbacks() {
		SampleRoot root = new SampleRoot();
		// isEquipped() fallback for 'equipped'
		assertEquals(true, evaluator.evaluate(root, "player.getInventory().equipped"));
		// rawCount() fallback for 'rawCount'
		assertEquals(64, evaluator.evaluate(root, "player.getInventory().rawCount"));
	}

	@Test
	void testListIndexing() {
		SampleRoot root = new SampleRoot();
		Object result = evaluator.evaluate(root, "player.getInventory().items[0].id");
		assertEquals("minecraft:diamond_sword", result);

		Object item1 = evaluator.evaluate(root, "player.getInventory().items[1].id");
		assertEquals("minecraft:shield", item1);
	}

	@Test
	void testArrayIndexing() {
		SampleRoot root = new SampleRoot();
		assertEquals("sharpness", evaluator.evaluate(root, "player.getInventory().tags[0]"));
		assertEquals("unbreaking", evaluator.evaluate(root, "player.getInventory().tags[1]"));

		ProblemException e = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.getInventory().tags[5]")
		);
		assertEquals(400, e.problem().status());
	}

	@Test
	void testCollectionIndexing() {
		SampleRoot root = new SampleRoot();
		assertEquals("weapon", evaluator.evaluate(root, "player.getInventory().categories[0]"));
		assertEquals("melee", evaluator.evaluate(root, "player.getInventory().categories[1]"));

		ProblemException e = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.getInventory().categories[5]")
		);
		assertEquals(400, e.problem().status());
	}

	@Test
	void testMapIndexing() {
		SampleRoot root = new SampleRoot();
		assertEquals("test", evaluator.evaluate(root, "tags[\"custom\"]"));
		assertEquals("test", evaluator.evaluate(root, "tags['custom']"));
		assertEquals(1, evaluator.evaluate(root, "tags[version]"));
	}

	@Test
	void testInvalidIndexCases() {
		SampleRoot root = new SampleRoot();

		// Negative index
		ProblemException e1 = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.getInventory().items[-1]")
		);
		assertEquals(400, e1.problem().status());

		// String index on non-map
		ProblemException e2 = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.name[\"key\"]")
		);
		assertEquals(400, e2.problem().status());

		// Invalid index expression on non-map
		ProblemException e3 = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.name[badIndex]")
		);
		assertEquals(400, e3.problem().status());
	}

	@Test
	void testIndexOutOfBounds() {
		SampleRoot root = new SampleRoot();
		ProblemException e = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.getInventory().items[99]")
		);
		assertEquals(400, e.problem().status());
	}

	@Test
	void testMissingField() {
		SampleRoot root = new SampleRoot();
		ProblemException e = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.nonExistentField")
		);
		assertEquals(400, e.problem().status());
	}

	@Test
	void testForbiddenMethodRejection() {
		SampleRoot root = new SampleRoot();
		ProblemException e1 = assertThrows(ProblemException.class, () -> evaluator.evaluate(root, "wait()"));
		assertEquals(403, e1.problem().status());

		ProblemException e2 = assertThrows(ProblemException.class, () -> evaluator.evaluate(root, "notify()"));
		assertEquals(403, e2.problem().status());

		ProblemException e3 = assertThrows(ProblemException.class, () -> evaluator.evaluate(root, "notifyAll()"));
		assertEquals(403, e3.problem().status());
	}

	@Test
	void testNullSegmentTraversalThrows() {
		SampleRoot root = new SampleRoot();
		ProblemException e = assertThrows(ProblemException.class, () ->
				evaluator.evaluate(root, "player.getInventory().nullItem.id")
		);
		assertEquals(400, e.problem().status());
		assertTrue(e.problem().detail().contains("Path evaluated to null"));
	}
}
