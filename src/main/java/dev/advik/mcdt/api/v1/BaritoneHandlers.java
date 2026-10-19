package dev.advik.mcdt.api.v1;

import dev.advik.mcdt.api.v1.dto.BaritoneDto.*;
import dev.advik.mcdt.baritone.BaritoneService;
import dev.advik.mcdt.config.McdtConfig;
import dev.advik.mcdt.exec.TaskQueue;
import dev.advik.mcdt.http.Problem;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.json.Json;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.openapi.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handlers for all high-level automation endpoints under {@code /v1/baritone/*}.
 */
public final class BaritoneHandlers {
	private BaritoneHandlers() {
	}

	public static final class StatusHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public StatusHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone",
				methods = {HttpMethod.GET},
				summary = "Baritone automation status",
				description = "Returns whether Baritone is installed, active process, current goal, and pathing state.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				responses = {
						@OpenApiResponse(status = "200", description = "Baritone status", content = {@OpenApiContent(from = StatusResponse.class)}),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				StatusResponse resp = baritoneService.getStatus();
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class PathHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public PathHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/path",
				methods = {HttpMethod.GET},
				summary = "Current path segments",
				description = "Returns block coordinates of current and next planned path segments for UI/overlays.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				responses = {
						@OpenApiResponse(status = "200", description = "Current path", content = {@OpenApiContent(from = PathResponse.class)}),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				PathResponse resp = baritoneService.getPath();
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class GotoHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public GotoHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/goto",
				methods = {HttpMethod.POST},
				summary = "Path to coordinates or block goal",
				description = "Starts pathing towards a block coordinate, column, proximity area, or nearest block type. Returns 202 with job ID.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = GotoRequest.class)}),
				responses = {
						@OpenApiResponse(status = "202", description = "Goal accepted and calculation started", content = {@OpenApiContent(from = JobResponse.class)}),
						@OpenApiResponse(status = "400", description = "Invalid coordinates or parameters", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "403", description = "Permission denied or multiplayer write disabled", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "503", description = "Task queue full", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "504", description = "Gateway timeout", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			GotoRequest req = ctx.bodyAsClass(GotoRequest.class);
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				JobResponse resp = baritoneService.startGoto(req);
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.status(202);
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class MineHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public MineHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/mine",
				methods = {HttpMethod.POST},
				summary = "Mine specific block types",
				description = "Automates mining given block identifiers up to specified quantity. Returns 202 with job ID.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = MineRequest.class)}),
				responses = {
						@OpenApiResponse(status = "202", description = "Mining job started", content = {@OpenApiContent(from = JobResponse.class)}),
						@OpenApiResponse(status = "400", description = "Invalid block names", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "403", description = "Permission denied", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			MineRequest req = ctx.bodyAsClass(MineRequest.class);
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				JobResponse resp = baritoneService.startMine(req);
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.status(202);
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class FollowHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public FollowHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/follow",
				methods = {HttpMethod.POST},
				summary = "Follow an entity or player",
				description = "Follows a target by player name, entity UUID, or object handle. Returns 202 with job ID.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = FollowRequest.class)}),
				responses = {
						@OpenApiResponse(status = "202", description = "Following job started", content = {@OpenApiContent(from = JobResponse.class)}),
						@OpenApiResponse(status = "400", description = "Invalid follow target", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "401", description = "Unauthorized", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "403", description = "Permission denied", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			FollowRequest req = ctx.bodyAsClass(FollowRequest.class);
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				JobResponse resp = baritoneService.startFollow(req);
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.status(202);
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class ExploreHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public ExploreHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/explore",
				methods = {HttpMethod.POST},
				summary = "Explore outward from coordinates",
				description = "Explores chunks outward from a given coordinate or current position. Returns 202 with job ID.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = ExploreRequest.class)}),
				responses = {
						@OpenApiResponse(status = "202", description = "Explore job started", content = {@OpenApiContent(from = JobResponse.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			ExploreRequest req = ctx.bodyAsClass(ExploreRequest.class);
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				JobResponse resp = baritoneService.startExplore(req);
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.status(202);
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class FarmHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public FarmHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/farm",
				methods = {HttpMethod.POST},
				summary = "Harvest and replant crops",
				description = "Automates harvesting mature crops and replanting seeds within a range. Returns 202 with job ID.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = FarmRequest.class)}),
				responses = {
						@OpenApiResponse(status = "202", description = "Farm job started", content = {@OpenApiContent(from = JobResponse.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			FarmRequest req = ctx.bodyAsClass(FarmRequest.class);
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				JobResponse resp = baritoneService.startFarm(req);
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.status(202);
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class BuildHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public BuildHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/build",
				methods = {HttpMethod.POST},
				summary = "Build schematic from folder",
				description = "Builds a schematic structure at an origin position. Returns 202 with job ID.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = BuildRequest.class)}),
				responses = {
						@OpenApiResponse(status = "202", description = "Build job started", content = {@OpenApiContent(from = JobResponse.class)}),
						@OpenApiResponse(status = "400", description = "Invalid schematic or coordinates", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			BuildRequest req = ctx.bodyAsClass(BuildRequest.class);
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				JobResponse resp = baritoneService.startBuild(req);
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.status(202);
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class CancelHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public CancelHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/cancel",
				methods = {HttpMethod.POST},
				summary = "Cancel all Baritone pathing and processes",
				description = "Immediately halts pathing and stops active goals, mining, building, or following.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				responses = {
						@OpenApiResponse(status = "200", description = "All processes cancelled", content = {@OpenApiContent(from = CancelResponse.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				CancelResponse resp = baritoneService.cancel();
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}

	public static final class JobHandler implements Handler {
		private final BaritoneService baritoneService;

		public JobHandler(BaritoneService baritoneService) {
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/jobs/{id}",
				methods = {HttpMethod.GET},
				summary = "Query Baritone job status",
				description = "Retrieves status, creation time, and message for an asynchronous Baritone job.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				pathParams = {
						@OpenApiParam(name = "id", description = "Job ID", required = true)
				},
				responses = {
						@OpenApiResponse(status = "200", description = "Job status", content = {@OpenApiContent(from = JobResponse.class)}),
						@OpenApiResponse(status = "404", description = "Job not found or expired", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			String id = ctx.pathParam("id");
			JobResponse job = baritoneService.getJob(id);
			ctx.contentType("application/json");
			ctx.result(Json.MAPPER.valueToTree(job).toString());
		}
	}

	public static final class SettingsGetHandler implements Handler {
		private final BaritoneService baritoneService;

		public SettingsGetHandler(BaritoneService baritoneService) {
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/settings",
				methods = {HttpMethod.GET},
				summary = "List all Baritone settings",
				description = "Returns every available Baritone setting with its type, default value, and current value.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				responses = {
						@OpenApiResponse(status = "200", description = "Map of all settings"),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			Map<String, SettingInfo> settings = baritoneService.getSettings();
			ctx.contentType("application/json");
			ctx.result(Json.MAPPER.valueToTree(settings).toString());
		}
	}

	public static final class SettingsPatchHandler implements Handler {
		private final BaritoneService baritoneService;

		public SettingsPatchHandler(BaritoneService baritoneService) {
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/settings",
				methods = {HttpMethod.PATCH},
				summary = "Update Baritone settings",
				description = "Updates one or more Baritone settings dynamically. Requires act permission.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				responses = {
						@OpenApiResponse(status = "200", description = "Updated settings map"),
						@OpenApiResponse(status = "400", description = "Unknown setting or invalid value", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@SuppressWarnings("unchecked")
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			Map<String, Object> patch = ctx.bodyAsClass(Map.class);
			Map<String, Object> updated = baritoneService.patchSettings(patch);
			ctx.contentType("application/json");
			ctx.result(Json.MAPPER.valueToTree(updated).toString());
		}
	}

	public static final class CommandHandler implements Handler {
		private final McdtConfig config;
		private final TaskQueue queue;
		private final BaritoneService baritoneService;

		public CommandHandler(McdtConfig config, TaskQueue queue, BaritoneService baritoneService) {
			this.config = config;
			this.queue = queue;
			this.baritoneService = baritoneService;
		}

		@OpenApi(
				path = "/v1/baritone/command",
				methods = {HttpMethod.POST},
				summary = "Execute raw Baritone command",
				description = "Runs a Baritone command string (e.g. 'goto 100 64 -200') directly. Requires act permission.",
				tags = {"Baritone (optional)"},
				security = {@OpenApiSecurity(name = "BearerAuth")},
				requestBody = @OpenApiRequestBody(content = {@OpenApiContent(from = CommandRequest.class)}),
				responses = {
						@OpenApiResponse(status = "200", description = "Command executed", content = {@OpenApiContent(from = CommandResponse.class)}),
						@OpenApiResponse(status = "400", description = "Invalid command string", content = {@OpenApiContent(from = Problem.class)}),
						@OpenApiResponse(status = "501", description = "Baritone not installed", content = {@OpenApiContent(from = Problem.class)})
				}
		)
		@Override
		public void handle(Context ctx) {
			if (!baritoneService.isInstalled()) {
				throw Problems.baritoneNotInstalled();
			}
			CommandRequest req = ctx.bodyAsClass(CommandRequest.class);
			long timeoutMs = config.requestTimeoutMs;
			CompletableFuture<Void> future = queue.submit(() -> {
				CommandResponse resp = baritoneService.executeCommand(req.command());
				return Json.MAPPER.valueToTree(resp);
			}, timeoutMs).thenAccept(res -> {
				ctx.header("X-MCDT-Tick", String.valueOf(res.tick()));
				ctx.contentType("application/json");
				ctx.result(res.body().toString());
			});
			ctx.future(() -> future);
		}
	}
}
