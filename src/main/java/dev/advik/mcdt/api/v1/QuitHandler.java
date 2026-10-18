package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.QuitDto;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.security.ConfirmManager;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;

public final class QuitHandler implements Handler {
	private final McdtConfig config;
	private final ConfirmManager confirmManager;

	public QuitHandler(McdtConfig config, ConfirmManager confirmManager) {
		this.config = config;
		this.confirmManager = confirmManager;
	}

	@OpenApi(
			path = "/v1/session/quit",
			methods = {HttpMethod.POST},
			summary = "Request game quit confirmation token",
			description = "Initiates two-step confirmation to safely quit the game client. Returns a single-use token valid for 30 seconds.",
			tags = {"Session"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			responses = {
					@OpenApiResponse(status = "202", description = "Confirmation token generated", content = {@OpenApiContent(from = QuitDto.RequestResponse.class)}),
					@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
					@OpenApiResponse(status = "403", description = "Quit disabled or permission denied", content = {@OpenApiContent(from = Problem.class)})
			}
	)
	@Override
	public void handle(Context ctx) {
		if (!config.allowQuit) {
			throw Problems.quitDisabled();
		}

		String auth = ctx.header("Authorization");
		String bearer = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7).trim() : "";

		ConfirmManager.TokenInfo tokenInfo = confirmManager.createToken(bearer, "quit");
		QuitDto.RequestResponse response = new QuitDto.RequestResponse(tokenInfo.token(), tokenInfo.expiresAt());

		ctx.status(202);
		ctx.contentType("application/json");
		try {
			ctx.result(Json.MAPPER.writeValueAsString(response));
		} catch (Exception e) {
			throw Problems.internal("Failed to serialize quit response: " + e.getMessage());
		}
	}
}
