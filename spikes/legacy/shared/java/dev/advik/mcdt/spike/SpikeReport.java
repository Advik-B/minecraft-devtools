package dev.advik.mcdt.spike;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Loader-agnostic spike checks. Java 8 only, no Minecraft types. */
public final class SpikeReport {
	public static volatile String loader = "unknown";
	public static volatile String minecraft = "unknown";
	public static volatile boolean mixinApplied;
	public static volatile File gameDir = new File(".");

	private SpikeReport() {
	}

	/** Starts a loopback HTTP server in-process and calls it, like MCDT's API does. */
	static String httpSelfTest() {
		HttpServer server = null;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/ping", exchange -> {
				byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();
			int port = server.getAddress().getPort();
			HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/ping").openConnection();
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			try (InputStream in = conn.getInputStream()) {
				byte[] chunk = new byte[256];
				int n;
				while ((n = in.read(chunk)) != -1) {
					buf.write(chunk, 0, n);
				}
			}
			String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
			return conn.getResponseCode() == 200 && "pong".equals(body) ? "ok" : "bad:" + conn.getResponseCode() + ":" + body;
		} catch (Throwable t) {
			return "error:" + t;
		} finally {
			if (server != null) {
				server.stop(0);
			}
		}
	}

	/** Logs the result line CI greps for and writes it to {@code mcdt-spike.txt} in the game directory. */
	public static void finish() {
		String http = httpSelfTest();
		boolean pass = mixinApplied && "ok".equals(http);
		String line = "MCDT-SPIKE RESULT status=" + (pass ? "PASS" : "FAIL")
			+ " loader=" + loader
			+ " mc=" + minecraft
			+ " java=" + System.getProperty("java.version")
			+ " mixin=" + mixinApplied
			+ " http=" + http;
		System.out.println(line);
		try (FileOutputStream out = new FileOutputStream(new File(gameDir, "mcdt-spike.txt"))) {
			out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		} catch (Throwable t) {
			System.out.println("MCDT-SPIKE could not write result file: " + t);
		}
	}
}
