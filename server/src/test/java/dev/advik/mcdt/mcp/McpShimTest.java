package dev.advik.mcdt.mcp;

import dev.advik.mcdt.McdtEnvironment;
import dev.advik.mcdt.api.v1.MockGameClient;
import dev.advik.mcdt.baritone.MockBaritoneService;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.config.Permission;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.http.HttpServer;
import dev.advik.mcdt.http.RequestLogWriter;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.BearerToken;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpShimTest {
	@TempDir
	Path tempDir;

	private McdtEnvironment env;
	private McdtConfig config;
	private BearerToken token;
	private TickClock clock;
	private TaskQueue queue;
	private RequestLogWriter logWriter;
	private MockGameClient gameClient;
	private MockBaritoneService baritoneService;
	private HttpServer server;

	@BeforeEach
	void setUp() {
		EventHub.resetForTesting();

		env = new McdtEnvironment(
				"0.1.0-SNAPSHOT",
				"1.21.4",
				tempDir,
				tempDir.resolve("config"),
				tempDir.resolve("logs"),
				tempDir.resolve("home"),
				"TestPlayer",
				"1.13.1",
				Instant.now()
		);

		config = new McdtConfig();
		config.preferredPort = 0;
		config.permission = Permission.ACT;

		token = BearerToken.of("test-mcp-shim-token");
		clock = new TickClock();
		queue = new TaskQueue(256, clock);
		logWriter = new RequestLogWriter(tempDir.resolve("logs"));
		gameClient = new MockGameClient();
		baritoneService = new MockBaritoneService();

		server = new HttpServer(env, config, token, queue, logWriter, Map.of(), gameClient, baritoneService);
	}

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
		}
		EventHub.resetForTesting();
	}

	@Test
	void testPythonShimStdioRelay() throws Exception {
		File scriptFile = new File("scripts/mcdt-mcp.py");
		assertTrue(scriptFile.exists(), "scripts/mcdt-mcp.py must exist");

		ProcessBuilder pb = new ProcessBuilder(
				"python",
				scriptFile.getAbsolutePath(),
				"--url", server.baseUrl(),
				"--token", token.value(),
				"--no-sse"
		);
		pb.redirectError(ProcessBuilder.Redirect.INHERIT);

		Process process = pb.start();
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

		// Send initialize request via stdin
		String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}";
		writer.write(initReq);
		writer.newLine();
		writer.flush();

		String line = reader.readLine();
		assertNotNull(line, "Shim must return a response");
		JsonNode json = Json.MAPPER.readTree(line);
		assertEquals("2.0", json.get("jsonrpc").asText());
		assertEquals(1, json.get("id").asInt());
		assertEquals("2024-11-05", json.get("result").get("protocolVersion").asText());

		// Send ping request
		String pingReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
		writer.write(pingReq);
		writer.newLine();
		writer.flush();

		String line2 = reader.readLine();
		assertNotNull(line2);
		JsonNode pingResp = Json.MAPPER.readTree(line2);
		assertEquals(2, pingResp.get("id").asInt());
		assertNotNull(pingResp.get("result"));

		// Close stdin and verify process exits cleanly
		writer.close();
		boolean finished = process.waitFor(5, TimeUnit.SECONDS);
		assertTrue(finished, "Shim process should finish after EOF on stdin");
		assertEquals(0, process.exitValue());
	}
}
