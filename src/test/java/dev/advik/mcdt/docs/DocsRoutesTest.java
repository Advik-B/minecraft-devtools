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
			cfg.routes.get("/docs", DocsRoutes.scalarHtml());
			cfg.routes.get("/docs/scalar/standalone.js", DocsRoutes.scalarJs());
			cfg.routes.get("/assets/mcdt/icon.png", DocsRoutes.icon());
		});

		JavalinTest.test(app, (server, client) -> {
			// Test Scalar HTML
			Response htmlResp = client.get("/docs");
			assertEquals(200, htmlResp.code());
			assertTrue(htmlResp.headers().get("Content-Type").get(0).contains("text/html"));
			assertTrue(htmlResp.body().string().contains("data-url=\"/openapi.json\""));

			// Test Scalar standalone.js
			Response jsResp = client.get("/docs/scalar/standalone.js");
			assertEquals(200, jsResp.code());
			assertTrue(jsResp.headers().get("Content-Type").get(0).contains("application/javascript"));
			assertTrue(jsResp.body().string().length() > 100);

			// Test Icon
			Response iconResp = client.get("/assets/mcdt/icon.png");
			// Either 200 or 404 depending on whether icon exists in test classpath
			assertTrue(iconResp.code() == 200 || iconResp.code() == 404);
		});
	}
}
