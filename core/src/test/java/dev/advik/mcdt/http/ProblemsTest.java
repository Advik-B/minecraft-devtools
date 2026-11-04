package dev.advik.mcdt.http;

import dev.advik.mcdt.json.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProblemsTest {

	@Test
	void testAllProblemFactories() {
		assertProblem(Problems.unauthorized("need token"), 401, Problems.UNAUTHORIZED);
		assertProblem(Problems.badHost("evil.com"), 403, Problems.BAD_HOST);
		assertProblem(Problems.originNotAllowed("http://bad.org"), 403, Problems.ORIGIN_NOT_ALLOWED);
		assertProblem(Problems.permissionDenied("act", "read"), 403, Problems.PERMISSION_DENIED);
		assertProblem(Problems.rateLimited(), 429, Problems.RATE_LIMITED);
		assertProblem(Problems.queueFull(), 503, Problems.QUEUE_FULL);
		assertProblem(Problems.timeout(5000), 504, Problems.TIMEOUT);
		assertProblem(Problems.shuttingDown(), 503, Problems.SHUTTING_DOWN);
		assertProblem(Problems.internal("failure"), 500, Problems.INTERNAL);
		assertProblem(Problems.noPlayer(), 409, Problems.NO_PLAYER);
		assertProblem(Problems.noWorld(), 409, Problems.NO_WORLD);
		assertProblem(Problems.blocksLimitExceeded(50000, 32768), 400, Problems.BLOCKS_LIMIT_EXCEEDED);
		assertProblem(Problems.invalidCoordinates("bad format"), 400, Problems.INVALID_COORDINATES);
		assertProblem(Problems.registryNotFound("minecraft:foo"), 404, Problems.REGISTRY_NOT_FOUND);
		assertProblem(Problems.invalidParameter("radius", "bad radius"), 400, Problems.INVALID_PARAMETER);
		assertProblem(Problems.screenshotFailed("gpu error"), 500, Problems.SCREENSHOT_FAILED);
		assertProblem(Problems.quitTokenInvalid(), 409, Problems.QUIT_TOKEN_INVALID);
		assertProblem(Problems.quitDisabled(), 403, Problems.QUIT_DISABLED);
		assertProblem(Problems.noScreen(), 409, Problems.NO_SCREEN);
		assertProblem(Problems.noContainer(), 409, Problems.NO_CONTAINER);
		assertProblem(Problems.slotOutOfBounds(10, 5), 400, Problems.SLOT_OUT_OF_BOUNDS);
		assertProblem(Problems.widgetNotFound(3), 404, Problems.WIDGET_NOT_FOUND);
		assertProblem(Problems.invalidAction("fly", "unknown action"), 400, Problems.INVALID_ACTION);
		assertProblem(Problems.handleNotFound("h_1234"), 404, Problems.HANDLE_NOT_FOUND);
		assertProblem(Problems.handleGone("h_1234"), 410, Problems.HANDLE_GONE);
		assertProblem(Problems.reflectionDenied("class blocked"), 403, Problems.REFLECTION_DENIED);
		assertProblem(Problems.classNotFound("com.example.Fake"), 404, Problems.CLASS_NOT_FOUND);
		assertProblem(Problems.fieldNotFound("secret", "MyClass"), 404, Problems.FIELD_NOT_FOUND);
		assertProblem(Problems.pathEvaluationFailed("a.b", "null pointer"), 400, Problems.PATH_EVALUATION_FAILED);
	}

	private void assertProblem(ProblemException e, int expectedStatus, String expectedType) {
		assertEquals(expectedStatus, e.problem().status());
		assertEquals(expectedType, e.problem().type());
		assertNotNull(e.problem().title());
		assertNotNull(e.problem().detail());
	}

	@Test
	void testProblemRecordAndJsonSerialization() throws Exception {
		Problem problem = new Problem("about:blank", "Bad Request", 400, "Invalid param", "/v1/test");
		assertEquals("about:blank", problem.type());
		assertEquals("Bad Request", problem.title());
		assertEquals(400, problem.status());
		assertEquals("Invalid param", problem.detail());
		assertEquals("/v1/test", problem.instance());

		Problem withNewInstance = problem.withInstance("/v1/updated");
		assertEquals("/v1/updated", withNewInstance.instance());

		String json = Json.MAPPER.writeValueAsString(withNewInstance);
		assertTrue(json.contains("\"status\":400"));
		assertTrue(json.contains("\"title\":\"Bad Request\""));
		assertTrue(json.contains("\"instance\":\"/v1/updated\""));
	}

	@Test
	void testProblemExceptionHeaders() {
		ProblemException e = Problems.unauthorized("no token");
		assertEquals("Bearer realm=\"mcdt\"", e.headers().get("WWW-Authenticate"));
	}
}
