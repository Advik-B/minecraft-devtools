package dev.advik.mcdt.spike.neo;

import io.javalin.Javalin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Starts Javalin from jar-in-jar libraries on the title screen, calls it over HTTP and WebSocket, then quits. */
@Mod(value = "mcdtspike", dist = Dist.CLIENT)
public final class NeoSpike {
	private int ticks;
	private boolean done;

	public NeoSpike(IEventBus modBus) {
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> onTick());
	}

	private void onTick() {
		if (done) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ticks++;
		if (ticks < 40 || !(mc.screen instanceof TitleScreen)) {
			return;
		}
		done = true;

		String http = "not-run";
		String ws = "not-run";
		String module = "?";
		Javalin app = null;
		try {
			module = String.valueOf(Javalin.class.getModule().getName());
			app = Javalin.create(cfg -> {
				cfg.startup.showJavalinBanner = false;
				cfg.routes.get("/ping", ctx -> ctx.json(Map.of("pong", true)));
				cfg.routes.ws("/echo", wsCfg -> wsCfg.onMessage(ctx -> ctx.send(ctx.message())));
			}).start("127.0.0.1", 0);
			int port = app.port();
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

			HttpResponse<String> response = client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping")).timeout(Duration.ofSeconds(5)).build(),
				HttpResponse.BodyHandlers.ofString());
			http = response.statusCode() == 200 && response.body().replace(" ", "").contains("\"pong\":true")
				? "ok" : "bad:" + response.statusCode() + ":" + response.body();

			CompletableFuture<String> echoed = new CompletableFuture<>();
			WebSocket socket = client.newWebSocketBuilder()
				.buildAsync(URI.create("ws://127.0.0.1:" + port + "/echo"), new WebSocket.Listener() {
					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						echoed.complete(data.toString());
						return null;
					}
				}).get(5, TimeUnit.SECONDS);
			socket.sendText("hello", true).get(5, TimeUnit.SECONDS);
			String reply = echoed.get(5, TimeUnit.SECONDS);
			ws = "hello".equals(reply) ? "ok" : "bad:" + reply;
			socket.abort();
		} catch (Throwable t) {
			if ("not-run".equals(http)) {
				http = "error:" + t;
			} else {
				ws = "error:" + t;
			}
			t.printStackTrace();
		} finally {
			if (app != null) {
				app.stop();
			}
		}

		boolean pass = "ok".equals(http) && "ok".equals(ws);
		String line = "MCDT-SPIKE RESULT status=" + (pass ? "PASS" : "FAIL")
			+ " loader=neoforge mc=1.21.1 java=" + System.getProperty("java.version")
			+ " javalinModule=" + module + " http=" + http + " ws=" + ws;
		System.out.println(line);
		try {
			Files.writeString(FMLPaths.GAMEDIR.get().resolve("mcdt-spike.txt"), line + "\n", StandardCharsets.UTF_8);
		} catch (Throwable t) {
			System.out.println("MCDT-SPIKE could not write result file: " + t);
		}
		mc.stop();
	}
}
