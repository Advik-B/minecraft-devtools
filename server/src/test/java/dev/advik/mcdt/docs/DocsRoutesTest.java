package dev.advik.mcdt.docs;

import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import io.javalin.testtools.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocsRoutesTest {

	@Test
	void testDocsRoutesHandlers() {
		Javalin app = Javalin.create(cfg -> {
			cfg.startup.showJavalinBanner = false;
			cfg.routes.get("/docs", DocsRoutes.redirectTo("/docs/scalar"));
			cfg.routes.get("/docs/scalar", DocsRoutes.scalarHtml());
			cfg.routes.get("/docs/scaler", DocsRoutes.redirectTo("/docs/scalar"));
			cfg.routes.get("/docs/scalar/standalone.js", DocsRoutes.scalarJs());
			cfg.routes.get("/assets/mcdt/icon.png", DocsRoutes.icon());
		});

		JavalinTest.test(app, (server, client) -> {
			// Test Canonical Scalar HTML at /docs/scalar
			Response htmlResp = client.get("/docs/scalar");
			assertEquals(200, htmlResp.code());
			assertTrue(htmlResp.headers().get("Content-Type").get(0).contains("text/html"));
			assertTrue(htmlResp.body().string().contains("data-url=\"/openapi.json\""));

			// Test /docs/scaler typo alias (redirects to /docs/scalar)
			Response scalerResp = client.get("/docs/scaler");
			assertEquals(302, scalerResp.code());
			assertEquals("/docs/scalar", scalerResp.headers().get("Location").get(0));

			// Test /docs redirect to /docs/scalar
			Response docsResp = client.get("/docs");
			assertEquals(302, docsResp.code());
			assertEquals("/docs/scalar", docsResp.headers().get("Location").get(0));

			// Test Scalar standalone.js
			Response jsResp = client.get("/docs/scalar/standalone.js");
			assertEquals(200, jsResp.code());
			assertTrue(jsResp.headers().get("Content-Type").get(0).contains("application/javascript"));
			assertTrue(jsResp.body().string().length() > 100);

			// Test Icon
			Response iconResp = client.get("/assets/mcdt/icon.png");
			assertTrue(iconResp.code() == 200 || iconResp.code() == 404);
		});
	}
}
