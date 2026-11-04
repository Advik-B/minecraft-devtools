package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ReflectionDto.ClassSourceResponse;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.decompiler.DecompilerService;
import dev.advik.mcdt.http.Problem;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

public final class ClassSourceHandler implements Handler {
	private final McdtConfig config;
	private final DecompilerService decompilerService;

	public ClassSourceHandler(McdtConfig config, DecompilerService decompilerService) {
		this.config = config;
		this.decompilerService = decompilerService;
	}

	@OpenApi(
			path = "/v1/classes/{name}/source",
			methods = {HttpMethod.GET},
			summary = "Decompile class source code",
			description = "Decompiles Minecraft and installed mod classes on-the-fly using an isolated Vineflower engine.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "name", description = "Fully-qualified class name", required = true)
			},
			queryParams = {
					@OpenApiParam(name = "remap", type = Boolean.class, description = "Whether to remap names to Mojang mappings if available (default true)"),
					@OpenApiParam(name = "format", description = "Response format: 'json' (default) or 'text'")
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Decompiled class source",
							content = {@OpenApiContent(from = ClassSourceResponse.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "403",
							description = "Class blocked by security denylist",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "404",
							description = "Class bytecode not found",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "503",
							description = "Decompiler disabled or service unavailable",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "504",
							description = "Gateway timeout",
							content = {@OpenApiContent(from = Problem.class)}
					)
			}
	)
	@Override
	public void handle(Context ctx) {
		String className = ctx.pathParam("name");
		boolean remap = ctx.queryParamAsClass("remap", Boolean.class).getOrDefault(true);
		String format = ctx.queryParam("format");
		String accept = ctx.header("Accept");
		boolean wantText = "text".equalsIgnoreCase(format) || (accept != null && accept.contains("text/plain"));

		ctx.future(() -> decompilerService.decompileAsync(className, remap).thenAccept(result -> {
			if (wantText) {
				ctx.contentType("text/plain; charset=utf-8").result(result.source());
			} else {
				ctx.json(new ClassSourceResponse(
						result.className(),
						result.runtimeName(),
						result.source(),
						result.remapped(),
						result.cached(),
						result.durationMs()
				));
			}
		}));
	}
}
