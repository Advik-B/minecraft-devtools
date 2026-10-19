package dev.advik.mcdt.baritone.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.*;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BetterBlockPos;
import dev.advik.mcdt.api.v1.dto.BaritoneDto.*;
import dev.advik.mcdt.baritone.BaritoneJobManager;
import dev.advik.mcdt.baritone.BaritoneService;
import dev.advik.mcdt.events.EventHub;
import dev.advik.mcdt.http.Problems;
import dev.advik.mcdt.reflection.HandleTable;
import dev.advik.mcdt.reflection.TypeConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.util.*;
import java.util.function.Predicate;

/**
 * Concrete implementation of BaritoneService interfacing directly with the Baritone API.
 * This class is isolated in the bridge package and is never referenced unless Baritone is loaded.
 */
public final class BaritoneBridge implements BaritoneService {
	private final HandleTable handleTable;
	private final BaritoneJobManager jobManager;
	private final IBaritone baritone;

	public BaritoneBridge(HandleTable handleTable, BaritoneJobManager jobManager) {
		this.handleTable = handleTable;
		this.jobManager = jobManager;
		this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

		// Register event listener for pathing events
		this.baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
			@Override
			public void onPathEvent(PathEvent event) {
				String eventName = event.name().toLowerCase(Locale.ROOT);
				EventHub.getInstance().publish("baritone.path", Map.of("event", eventName));

				if (event == PathEvent.AT_GOAL) {
					jobManager.updateActiveJob("completed", "Arrived at goal");
				} else if (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED) {
					jobManager.updateActiveJob("failed", "Path calculation failed: " + event.name());
				} else if (event == PathEvent.CANCELED) {
					jobManager.updateActiveJob("cancelled", "Pathing was cancelled");
				} else if (event == PathEvent.CALC_FINISHED_NOW_EXECUTING) {
					jobManager.updateActiveJob("running", "Executing path");
				}
			}
		});
	}

	@Override
	public boolean isInstalled() {
		return true;
	}

	@Override
	public StatusResponse getStatus() {
		String activeProcessName = null;
		Optional<IBaritoneProcess> inControl = baritone.getPathingControlManager().mostRecentInControl();
		if (inControl.isPresent() && inControl.get().isActive()) {
			activeProcessName = inControl.get().displayName();
		} else if (baritone.getCustomGoalProcess().isActive()) {
			activeProcessName = "CustomGoalProcess";
		} else if (baritone.getMineProcess().isActive()) {
			activeProcessName = "MineProcess";
		} else if (baritone.getFollowProcess().isActive()) {
			activeProcessName = "FollowProcess";
		} else if (baritone.getExploreProcess().isActive()) {
			activeProcessName = "ExploreProcess";
		} else if (baritone.getFarmProcess().isActive()) {
			activeProcessName = "FarmProcess";
		} else if (baritone.getBuilderProcess().isActive()) {
			activeProcessName = "BuilderProcess";
		}

		IPathingBehavior pathing = baritone.getPathingBehavior();
		Goal goal = pathing.getGoal();
		GoalInfo goalInfo = null;
		if (goal != null) {
			Map<String, Object> params = new LinkedHashMap<>();
			if (goal instanceof GoalBlock gb) {
				params.put("x", gb.x);
				params.put("y", gb.y);
				params.put("z", gb.z);
			} else if (goal instanceof GoalXZ gx) {
				params.put("x", gx.getX());
				params.put("z", gx.getZ());
			} else if (goal instanceof GoalNear gn) {
				params.put("pos", gn.getGoalPos());
			} else if (goal instanceof GoalGetToBlock gtb) {
				params.put("x", gtb.x);
				params.put("y", gtb.y);
				params.put("z", gtb.z);
			}
			goalInfo = new GoalInfo(goal.getClass().getSimpleName(), params);
		}

		Double ticksRemaining = pathing.estimatedTicksToGoal().orElse(null);
		PathingInfo pathingInfo = new PathingInfo(
				pathing.hasPath(),
				pathing.isPathing(),
				pathing.getInProgress().isPresent(),
				ticksRemaining
		);

		return new StatusResponse(true, activeProcessName, goalInfo, pathingInfo);
	}

	@Override
	public PathResponse getPath() {
		IPathingBehavior pathing = baritone.getPathingBehavior();
		Optional<IPath> pathOpt = pathing.getPath();
		if (pathOpt.isEmpty()) {
			return new PathResponse(false, List.of(), List.of());
		}

		IPath path = pathOpt.get();
		List<BlockPosDto> currentSegment = new ArrayList<>();
		for (BetterBlockPos pos : path.positions()) {
			currentSegment.add(new BlockPosDto(pos.x, pos.y, pos.z));
		}

		return new PathResponse(true, currentSegment, List.of());
	}

	@Override
	public JobResponse startGoto(GotoRequest req) {
		Goal goal;
		if (req.block() != null && !req.block().isBlank()) {
			ResourceLocation rl = ResourceLocation.parse(req.block());
			Block b = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
			if (b == null) {
				throw Problems.badRequest("Unknown block type: " + req.block());
			}
			baritone.getGetToBlockProcess().getToBlock(b);
			JobResponse job = jobManager.createJob("goto", "Going to block " + req.block());
			EventHub.getInstance().publish("baritone.process", Map.of("process", "GetToBlockProcess", "active", true));
			return job;
		}

		if (req.x() != null && req.y() != null && req.z() != null) {
			BlockPos target = new BlockPos(req.x(), req.y(), req.z());
			if (req.radius() != null && req.radius() > 0) {
				goal = new GoalNear(target, req.radius());
			} else {
				goal = new GoalBlock(target);
			}
		} else if (req.x() != null && req.z() != null) {
			goal = new GoalXZ(req.x(), req.z());
		} else {
			throw Problems.badRequest("Goto requires block, {x, y, z}, or {x, z}");
		}

		baritone.getCustomGoalProcess().setGoalAndPath(goal);
		JobResponse job = jobManager.createJob("goto", "Going to " + goal);
		EventHub.getInstance().publish("baritone.process", Map.of("process", "CustomGoalProcess", "active", true));
		return job;
	}

	@Override
	public JobResponse startMine(MineRequest req) {
		if (req.blocks().isEmpty()) {
			throw Problems.badRequest("Mine requires at least one block type");
		}
		int quantity = req.quantity() != null && req.quantity() > 0 ? req.quantity() : 1;
		baritone.getMineProcess().mineByName(quantity, req.blocks().toArray(String[]::new));
		JobResponse job = jobManager.createJob("mine", "Mining " + quantity + " of " + req.blocks());
		EventHub.getInstance().publish("baritone.process", Map.of("process", "MineProcess", "active", true));
		return job;
	}

	@Override
	public JobResponse startFollow(FollowRequest req) {
		Predicate<Entity> predicate;
		String targetDesc;
		if (req.name() != null && !req.name().isBlank()) {
			predicate = e -> req.name().equalsIgnoreCase(e.getName().getString());
			targetDesc = "player " + req.name();
		} else if (req.uuid() != null && !req.uuid().isBlank()) {
			predicate = e -> req.uuid().equalsIgnoreCase(e.getUUID().toString());
			targetDesc = "UUID " + req.uuid();
		} else if (req.handle() != null && !req.handle().isBlank()) {
			Object targetObj = handleTable.get(req.handle());
			if (targetObj == null) {
				throw Problems.handleNotFound(req.handle());
			}
			predicate = e -> e == targetObj;
			targetDesc = "handle " + req.handle();
		} else {
			throw Problems.badRequest("Follow requires name, uuid, or handle");
		}

		baritone.getFollowProcess().follow(predicate);
		JobResponse job = jobManager.createJob("follow", "Following " + targetDesc);
		EventHub.getInstance().publish("baritone.process", Map.of("process", "FollowProcess", "active", true));
		return job;
	}

	@Override
	public JobResponse startExplore(ExploreRequest req) {
		int x = req.x() != null ? req.x() : 0;
		int z = req.z() != null ? req.z() : 0;
		baritone.getExploreProcess().explore(x, z);
		JobResponse job = jobManager.createJob("explore", "Exploring from " + x + ", " + z);
		EventHub.getInstance().publish("baritone.process", Map.of("process", "ExploreProcess", "active", true));
		return job;
	}

	@Override
	public JobResponse startFarm(FarmRequest req) {
		int range = req.range() != null && req.range() > 0 ? req.range() : 10;
		if (req.origin() != null) {
			baritone.getFarmProcess().farm(range, new BlockPos(req.origin().x(), req.origin().y(), req.origin().z()));
		} else {
			baritone.getFarmProcess().farm(range);
		}
		JobResponse job = jobManager.createJob("farm", "Farming range " + range);
		EventHub.getInstance().publish("baritone.process", Map.of("process", "FarmProcess", "active", true));
		return job;
	}

	@Override
	public JobResponse startBuild(BuildRequest req) {
		if (req.name() == null || req.name().isBlank()) {
			throw Problems.badRequest("Build requires schematic 'name'");
		}
		BlockPos origin = req.origin() != null
				? new BlockPos(req.origin().x(), req.origin().y(), req.origin().z())
				: BlockPos.ZERO;

		boolean started = baritone.getBuilderProcess().build(req.name(), origin);
		if (!started) {
			throw Problems.badRequest("Schematic '" + req.name() + "' could not be loaded from schematics directory");
		}
		JobResponse job = jobManager.createJob("build", "Building schematic " + req.name() + " at " + origin);
		EventHub.getInstance().publish("baritone.process", Map.of("process", "BuilderProcess", "active", true));
		return job;
	}

	@Override
	public CancelResponse cancel() {
		baritone.getPathingBehavior().cancelEverything();
		jobManager.updateActiveJob("cancelled", "Cancelled by request");
		EventHub.getInstance().publish("baritone.path", Map.of("event", "canceled"));
		return new CancelResponse(true);
	}

	@Override
	public JobResponse getJob(String jobId) {
		JobResponse job = jobManager.getJob(jobId);
		if (job == null) {
			throw Problems.jobNotFound(jobId);
		}
		return job;
	}

	@Override
	public Map<String, SettingInfo> getSettings() {
		Settings settings = BaritoneAPI.getSettings();
		Map<String, SettingInfo> map = new LinkedHashMap<>();
		for (Settings.Setting<?> s : settings.allSettings) {
			if (s.isJavaOnly()) {
				continue;
			}
			map.put(s.getName(), new SettingInfo(s.getName(), s.getValueClass().getSimpleName(), s.defaultValue, s.value));
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Map<String, Object> patchSettings(Map<String, Object> patch) {
		if (patch == null || patch.isEmpty()) {
			return Map.of();
		}
		Settings settings = BaritoneAPI.getSettings();
		Map<String, Object> updated = new LinkedHashMap<>();

		for (Map.Entry<String, Object> entry : patch.entrySet()) {
			String key = entry.getKey();
			Settings.Setting<?> s = settings.byLowerName.get(key.toLowerCase(Locale.ROOT));
			if (s == null || s.isJavaOnly()) {
				throw Problems.badRequest("Unknown Baritone setting: " + key);
			}

			Object converted = TypeConverter.convert(entry.getValue(), s.getValueClass(), handleTable);
			((Settings.Setting<Object>) s).value = converted;
			updated.put(s.getName(), s.value);
		}

		return updated;
	}

	@Override
	public CommandResponse executeCommand(String command) {
		if (command == null || command.isBlank()) {
			throw Problems.badRequest("Command cannot be null or empty");
		}
		boolean ok = baritone.getCommandManager().execute(command);
		return new CommandResponse(ok, command);
	}
}
