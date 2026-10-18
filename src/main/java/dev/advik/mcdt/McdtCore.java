package dev.advik.mcdt;

import dev.advik.mcdt.config.ConfigStore;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.discovery.DiscoveryFile;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.exec.TickClock;
import dev.advik.mcdt.http.HttpServer;
import dev.advik.mcdt.http.RequestLogWriter;
import dev.advik.mcdt.security.BearerToken;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Composition root for MCDT's non-game systems: config, authentication token, request logging, task queue,
 * HTTP server, and discovery files.
 */
public final class McdtCore implements AutoCloseable {
	private final McdtEnvironment env;
	private final McdtConfig config;
	private final BearerToken token;
	private final TaskQueue queue;
	private final RequestLogWriter logWriter;
	private final HttpServer server;
	private final DiscoveryFile discovery;
	private final Thread shutdownHook;

	public static McdtCore start(McdtEnvironment env) throws IOException {
		return start(env, System.getenv());
	}

	public static McdtCore start(McdtEnvironment env, Map<String, String> envOverrides) throws IOException {
		return start(env, envOverrides, dev.advik.mcdt.game.MinecraftGameClient.getInstance());
	}

	public static McdtCore start(McdtEnvironment env, Map<String, String> envOverrides, dev.advik.mcdt.game.GameClient gameClient) throws IOException {
		Files.createDirectories(env.configDir());
		Files.createDirectories(env.logDir());

		ConfigStore configStore = new ConfigStore(env.configDir().resolve("config.json"));
		McdtConfig config = configStore.load();

		BearerToken token = BearerToken.generate();
		token.writeTo(env.configDir().resolve("token"));

		TickClock clock = new TickClock();
		TaskQueue queue = new TaskQueue(config.queueCapacity, clock);
		RequestLogWriter logWriter = new RequestLogWriter(env.logDir());

		HttpServer server = new HttpServer(env, config, token, queue, logWriter, envOverrides, gameClient);

		long pid = ProcessHandle.current().pid();
		DiscoveryFile discovery = new DiscoveryFile(env.instancesDir(), env.configDir(), pid);
		server.setOnQuitCallback(discovery::delete);
		discovery.write(new DiscoveryFile.Info(
				server.baseUrl(),
				server.port(),
				pid,
				env.mcdtVersion(),
				env.gameVersion(),
				env.gameDir().toAbsolutePath().toString(),
				env.playerName(),
				env.startTime(),
				token.value()
		));

		McdtCore core = new McdtCore(env, config, token, queue, logWriter, server, discovery);
		Runtime.getRuntime().addShutdownHook(core.shutdownHook);
		return core;
	}

	private McdtCore(
			McdtEnvironment env,
			McdtConfig config,
			BearerToken token,
			TaskQueue queue,
			RequestLogWriter logWriter,
			HttpServer server,
			DiscoveryFile discovery
	) {
		this.env = env;
		this.config = config;
		this.token = token;
		this.queue = queue;
		this.logWriter = logWriter;
		this.server = server;
		this.discovery = discovery;
		this.shutdownHook = new Thread(this::cleanupOnShutdown, "MCDT shutdown hook");
	}

	public McdtEnvironment env() {
		return env;
	}

	public McdtConfig config() {
		return config;
	}

	public BearerToken token() {
		return token;
	}

	public TaskQueue queue() {
		return queue;
	}

	public HttpServer server() {
		return server;
	}

	public DiscoveryFile discovery() {
		return discovery;
	}

	private void cleanupOnShutdown() {
		try {
			discovery.delete();
			server.close();
			queue.close();
			logWriter.close();
		} catch (Throwable t) {
			// JVM is halting; nothing more we can do
		}
	}

	@Override
	public void close() {
		try {
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
		} catch (IllegalStateException ignored) {
			// Already shutting down
		}
		cleanupOnShutdown();
	}
}
