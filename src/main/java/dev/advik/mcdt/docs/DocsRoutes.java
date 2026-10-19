package dev.advik.mcdt.docs;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.http.Problems;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the offline Scalar documentation UI and cached static assets.
 */
public final class DocsRoutes {
	private static final String SCALAR_HTML = """
			<!doctype html>
			<html lang="en">
			  <head>
			    <meta charset="utf-8" />
			    <meta name="viewport" content="width=device-width, initial-scale=1" />
			    <title>Minecraft DevTools</title>
			    <link rel="icon" type="image/svg+xml" href="/icon.svg" />
			    <link rel="alternate icon" type="image/png" href="/assets/mcdt/icon.png" />
			    <style>
			      body {
			        margin: 0;
			        padding: 0;
			      }
			    </style>
			  </head>
			  <body>
			    <script
			      id="api-reference"
			      data-url="/openapi.json"
			      data-configuration='{"theme":"purple","layout":"modern"}'></script>
			    <script src="/docs/scalar/standalone.js"></script>
			  </body>
			</html>
			""";

	private static byte[] cachedScalarJs;

	private DocsRoutes() {
	}

	public static Handler scalarHtml() {
		return ctx -> {
			ctx.contentType("text/html; charset=utf-8");
			ctx.header("Content-Security-Policy", "default-src 'self' 'unsafe-inline' 'unsafe-eval' data:;");
			ctx.result(SCALAR_HTML);
		};
	}

	public static Handler redirectTo(String location) {
		return ctx -> ctx.redirect(location);
	}

	public static Handler scalarJs() {
		return ctx -> {
			byte[] bytes = getScalarJsBytes();
			if (bytes == null) {
				throw Problems.of(404, Problems.NOT_FOUND, "Not found", "Scalar script bundle not found in resources");
			}
			ctx.contentType("application/javascript; charset=utf-8");
			ctx.header("Cache-Control", "public, max-age=86400, immutable");
			ctx.result(bytes);
		};
	}

	public static Handler icon() {
		return ctx -> {
			try (InputStream in = DocsRoutes.class.getResourceAsStream("/assets/mcdt/icon.png")) {
				if (in == null) {
					ctx.status(404);
					return;
				}
				ctx.contentType("image/png");
				ctx.header("Cache-Control", "public, max-age=86400");
				ctx.result(in.readAllBytes());
			}
		};
	}

	public static Handler iconSvg() {
		return ctx -> {
			try (InputStream in = DocsRoutes.class.getResourceAsStream("/assets/mcdt/icon.svg")) {
				if (in == null) {
					ctx.status(404);
					return;
				}
				ctx.contentType("image/svg+xml");
				ctx.header("Cache-Control", "public, max-age=86400");
				ctx.result(in.readAllBytes());
			}
		};
	}

	private static synchronized byte[] getScalarJsBytes() {
		if (cachedScalarJs == null) {
			try (InputStream in = DocsRoutes.class.getResourceAsStream("/mcdt-web/scalar/standalone.js")) {
				if (in != null) {
					cachedScalarJs = in.readAllBytes();
				}
			} catch (IOException e) {
				Mcdt.LOGGER.warn("Failed to load Scalar bundle from classpath: {}", e.getMessage());
			}
		}
		return cachedScalarJs;
	}
}
