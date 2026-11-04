package dev.advik.mcdt;

import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.baritone.MockBaritoneService;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.http.HttpServer;
import dev.advik.mcdt.http.RequestLogWriter;
import dev.advik.mcdt.security.BearerToken;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Standalone test runner that starts the real MCDT Javalin server with MockGameClient,
 * MockBaritoneService (installed=true), decompiler enabled, and the Svelte 5 WebUI
 * served from web/dist at http://127.0.0.1:25580.
 */
public final class TestServerRunner {
	public static void main(String[] args) throws Exception {
		System.setProperty("mcdt.webDist", "web/dist");

		Path runDir = Path.of("run").toAbsolutePath();
		Path configDir = runDir.resolve("config/mcdt");
		Path logDir = runDir.resolve("logs");
		Path homeDir = Path.of(System.getProperty("user.home"), ".mcdt");

		Files.createDirectories(configDir);
		Files.createDirectories(logDir);

		String tokenValue = "test-secret-token";
		Files.writeString(configDir.resolve("token"), tokenValue);

		McdtEnvironment env = new McdtEnvironment(
				"0.1.0-SNAPSHOT",
				"1.21.4",
				runDir,
				configDir,
				logDir,
				homeDir,
				"DevPlayer",
				"1.21.4-v0.1.0",
				Instant.now()
		);

		McdtConfig config = new McdtConfig();
		config.preferredPort = 25580;
		config.permission = Permission.REFLECT_WRITE;
		config.enableDecompiler = true;
		config.decompilerCacheSize = 100;
		config.originAllowlist = List.of(
				"http://localhost:5173",
				"http://127.0.0.1:5173",
				"http://localhost:25580",
				"http://127.0.0.1:25580"
		);
		config.allowedMultiplayerServers = List.of("*");

		BearerToken token = BearerToken.of(tokenValue);
		TickClock clock = new TickClock();
		TaskQueue queue = new TaskQueue(256, clock);
		RequestLogWriter logWriter = new RequestLogWriter(logDir);
		MockGameClient mockGameClient = new MockGameClient();
		MockBaritoneService mockBaritone = new MockBaritoneService();
		mockBaritone.installed = true;

		// Client tick loop (20 TPS) to drain queue
		Thread tickThread = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					clock.advance();
					queue.drain(TimeUnit.MILLISECONDS.toNanos(45));
					Thread.sleep(50);
				} catch (InterruptedException e) {
					break;
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}, "mcdt-tick-thread");
		tickThread.setDaemon(true);
		tickThread.start();

		HttpServer server = new HttpServer(
				env,
				config,
				token,
				queue,
				logWriter,
				Map.of(),
				mockGameClient,
				mockBaritone
		);

		System.out.println("=================================================================");
		System.out.println("MCDT Live Test Server started!");
		System.out.println("URL:      http://127.0.0.1:" + server.port() + "/#token=" + tokenValue);
		System.out.println("Port:     " + server.port());
		System.out.println("Token:    " + tokenValue);
		System.out.println("Baritone: Installed (1.21.4-v0.1.0)");
		System.out.println("Decompiler: Enabled");
		System.out.println("=================================================================");

		// Keep process alive indefinitely
		Thread.currentThread().join();
	}
}
