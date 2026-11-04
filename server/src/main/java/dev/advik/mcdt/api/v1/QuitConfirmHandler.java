package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.QuitDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.game.GameClient;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.ConfirmManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class QuitConfirmHandler implements Handler {
	private final McdtConfig config;
	private final ConfirmManager confirmManager;
	private final GameClient gameClient;
	private final Runnable onQuitCallback;

	public QuitConfirmHandler(McdtConfig config, ConfirmManager confirmManager, GameClient gameClient, Runnable onQuitCallback) {
		this.config = config;
		this.confirmManager = confirmManager;
		this.gameClient = gameClient;
		this.onQuitCallback = onQuitCallback;
	}

	@OpenApi(
			path = "/v1/session/quit/confirm",
			methods = {HttpMethod.POST},
			summary = "Confirm game quit",
			description = "Confirms shutdown using single-use confirmation token, flushes response, and closes client.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = QuitDto.ConfirmRequest.class)}),
			responses = {
					@OpenApiResponse(status = "200", description = "Shutdown accepted", content = {@OpenApiContent(from = QuitDto.ConfirmResponse.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Quit disabled or permission denied", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "409", description = "Invalid, expired, or reused confirmation token", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		if (!config.allowQuit) {
			throw Problems.quitDisabled();
		}

		QuitDto.ConfirmRequest request;
		try {
			request = ctx.bodyAsClass(QuitDto.ConfirmRequest.class);
		} catch (Exception e) {
			throw Problems.quitTokenInvalid();
		}

		String auth = ctx.header("Authorization");
		String bearer = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : "";

		confirmManager.validateAndConsume(request.confirmToken(), bearer, "quit");

		QuitDto.ConfirmResponse response = new QuitDto.ConfirmResponse("shutting_down", "Game client is shutting down cleanly");
		ctx.status(200);
		ctx.contentType("application/json");
		try {
			ctx.result(Json.MAPPER.writeValueAsString(response));
		} catch (Exception e) {
			throw Problems.internal("Failed to serialize response: " + e.getMessage());
		}

		// Schedule shutdown after response flushes
		CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> {
			try {
				if (onQuitCallback != null) {
					onQuitCallback.run();
				}
			} finally {
				gameClient.quitGame();
			}
		});
	}
}
