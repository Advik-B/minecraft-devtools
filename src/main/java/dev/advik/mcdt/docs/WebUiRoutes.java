package dev.advik.mcdt.docs;

import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.http.Problems;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the compiled Svelte WebUI dashboard and static assets with SPA fallback.
 */
public final class WebUiRoutes {
	private static final String CLASSPATH_PREFIX = "/mcdt-web/dist";
	private static final Map<String, byte[]> CACHED_ASSETS = new ConcurrentHashMap<>();
	private static final Path DEV_WEB_DIST = Path.of("web", "dist");

	private WebUiRoutes() {
	}

	public static Handler serveApp() {
		return ctx -> {
			String path = ctx.path();

			// If root, serve index.html
			if ("/".equals(path) || path.isEmpty()) {
				serveIndex(ctx);
				return;
			}

			// Try to serve static asset matching exact path
			byte[] content = loadAsset(path);
			if (content != null) {
				serveBytes(ctx, path, content);
				return;
			}

			// If path starts with /assets/ or /docs/, let other handlers or 404 take it
			if (path.startsWith("/docs") || path.startsWith("/v1") || path.startsWith("/mcp") || path.equals("/openapi.json")) {
				throw Problems.notFound("Path not found: " + path);
			}

			// SPA fallback: for any other GET requests from browser navigation, serve index.html
			if ("GET".equalsIgnoreCase(ctx.method().name())) {
				serveIndex(ctx);
				return;
			}

			throw Problems.notFound("Path not found: " + path);
		};
	}

	public static Handler serveAsset() {
		return ctx -> {
			String path = ctx.path();
			byte[] content = loadAsset(path);
			if (content == null) {
				throw Problems.notFound("Asset not found: " + path);
			}
			serveBytes(ctx, path, content);
		};
	}

	private static void serveIndex(Context ctx) {
		byte[] indexBytes = loadAsset("/index.html");
		if (indexBytes == null) {
			// Fallback placeholder if frontend hasn't been built yet
			ctx.contentType("text/html; charset=utf-8");
			ctx.result("""
					<!doctype html>
					<html>
					<head><title>Minecraft DevTools - WebUI</title><link rel="icon" type="image/svg+xml" href="/icon.svg" /></head>
					<body style="font-family:sans-serif;background:#0b0f19;color:#e2e8f0;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
					  <div style="text-align:center;">
					    <h2>Minecraft DevTools WebUI</h2>
					    <p>Web dashboard assets are building or not packaged. Access Scalar at <a style="color:#a78bfa;" href="/docs/scalar">/docs/scalar</a> or ReDoc at <a style="color:#38bdf8;" href="/docs/redoc">/docs/redoc</a>.</p>
					  </div>
					</body>
					</html>
					""");
			return;
		}

		ctx.contentType("text/html; charset=utf-8");
		ctx.header("Content-Security-Policy", "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob: ws: wss:; frame-src 'self';");
		ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
		ctx.result(indexBytes);
	}

	private static void serveBytes(Context ctx, String path, byte[] content) {
		ctx.contentType(getMimeType(path));
		if (path.startsWith("/assets/")) {
			ctx.header("Cache-Control", "public, max-age=31536000, immutable");
		} else {
			ctx.header("Cache-Control", "public, max-age=3600");
		}
		ctx.result(content);
	}

	private static byte[] loadAsset(String path) {
		String cleanPath = path.startsWith("/") ? path : "/" + path;

		// 1. Check in-memory cache
		byte[] cached = CACHED_ASSETS.get(cleanPath);
		if (cached != null) {
			return cached;
		}

		// 2. Try development filesystem directory if running in local project
		File devFile = DEV_WEB_DIST.resolve(cleanPath.substring(1)).toFile();
		if (devFile.isFile()) {
			try (InputStream in = new FileInputStream(devFile)) {
				byte[] bytes = in.readAllBytes();
				// Don't cache in dev to allow hot edits
				return bytes;
			} catch (IOException ignored) {
			}
		}

		// 3. Try classpath resources
		String resourcePath = CLASSPATH_PREFIX + cleanPath;
		try (InputStream in = WebUiRoutes.class.getResourceAsStream(resourcePath)) {
			if (in != null) {
				byte[] bytes = in.readAllBytes();
				CACHED_ASSETS.put(cleanPath, bytes);
				return bytes;
			}
		} catch (IOException e) {
			Mcdt.LOGGER.warn("Failed reading asset {}: {}", resourcePath, e.getMessage());
		}

		return null;
	}

	private static String getMimeType(String path) {
		String lower = path.toLowerCase();
		if (lower.endsWith(".html")) return "text/html; charset=utf-8";
		if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
		if (lower.endsWith(".css")) return "text/css; charset=utf-8";
		if (lower.endsWith(".svg")) return "image/svg+xml";
		if (lower.endsWith(".png")) return "image/png";
		if (lower.endsWith(".ico")) return "image/x-icon";
		if (lower.endsWith(".json")) return "application/json; charset=utf-8";
		if (lower.endsWith(".woff2")) return "font/woff2";
		if (lower.endsWith(".woff")) return "font/woff";
		return "application/octet-stream";
	}
}
