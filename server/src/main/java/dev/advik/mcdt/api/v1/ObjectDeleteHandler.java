package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.ReflectionDto.HandleDeleteResponse;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import dev.advik.mcdt.reflection.HandleTable;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.openapi.OpenApiSecurity;
import io.javalin.openapi.OpenApiParam;

public final class ObjectDeleteHandler implements Handler {
	private final HandleTable handleTable;

	public ObjectDeleteHandler(HandleTable handleTable) {
		this.handleTable = handleTable;
	}

	@OpenApi(
			path = "/v1/objects/{handle}",
			methods = {HttpMethod.DELETE},
			summary = "Release an object handle early",
			description = "Releases an allocated handle from the HandleTable.",
			tags = {"Reflection"},
			security = {@OpenApiSecurity(name = "BearerAuth")},
			pathParams = {
					@OpenApiParam(name = "handle", description = "Object handle to release", required = true)
			},
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Handle released",
							content = {@OpenApiContent(from = HandleDeleteResponse.class)}
					),
					@OpenApiResponse(
							status = "400",
							description = "Cannot release named root handle",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "401",
							description = "Unauthorized",
							content = {@OpenApiContent(from = Problem.class)}
					),
					@OpenApiResponse(
							status = "404",
							description = "Handle not found or already released",
							content = {@OpenApiContent(from = Problem.class)}
					)
			}
	)
	@Override
	public void handle(Context ctx) {
		String handle = ctx.pathParam("handle");
		if (HandleTable.isRoot(handle)) {
			throw Problems.badRequest("Cannot release root handle '" + handle + "'");
		}

		boolean released = handleTable.release(handle);
		if (!released) {
			throw Problems.handleNotFound(handle);
		}

		ctx.status(200);
		ctx.contentType("application/json");
		try {
			ctx.result(Json.MAPPER.writeValueAsString(new HandleDeleteResponse(handle, true)));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
