package dev.advik.mcdt.baritone;

import dev.advik.mcdt.api.v1.dto.BaritoneDto.*;
import dev.advik.mcdt.http.Problems;

import java.time.Instant;
import java.util.*;

public class MockBaritoneService implements BaritoneService {
	public boolean installed = true;
	public String activeProcess = "CustomGoalProcess";
	public boolean hasPath = true;
	public final Map<String, JobResponse> jobs = new HashMap<>();
	public final Map<String, SettingInfo> settings = new LinkedHashMap<>();
	public final List<String> executedCommands = new ArrayList<>();

	public MockBaritoneService() {
		settings.put("allowBreak", new SettingInfo("allowBreak", "Boolean", true, true));
		settings.put("allowPlace", new SettingInfo("allowPlace", "Boolean", true, true));
		settings.put("primaryTimeoutMS", new SettingInfo("primaryTimeoutMS", "Long", 2000L, 2000L));
	}

	@Override
	public boolean isInstalled() {
		return installed;
	}

	@Override
	public StatusResponse getStatus() {
		if (!installed) throw Problems.baritoneNotInstalled();
		GoalInfo goal = new GoalInfo("GoalBlock", Map.of("x", 100, "y", 64, "z", -200));
		PathingInfo pathing = new PathingInfo(hasPath, true, false, 20.0);
		return new StatusResponse(true, activeProcess, goal, pathing);
	}

	@Override
	public PathResponse getPath() {
		if (!installed) throw Problems.baritoneNotInstalled();
		List<BlockPosDto> segment = List.of(
				new BlockPosDto(100, 64, -200),
				new BlockPosDto(101, 64, -200),
				new BlockPosDto(102, 64, -200)
		);
		return new PathResponse(hasPath, segment, List.of());
	}

	@Override
	public JobResponse startGoto(GotoRequest req) {
		if (!installed) throw Problems.baritoneNotInstalled();
		String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
		Instant now = Instant.now();
		JobResponse job = new JobResponse(id, "goto", "started", "Going to goal", now, now);
		jobs.put(id, job);
		return job;
	}

	@Override
	public JobResponse startMine(MineRequest req) {
		if (!installed) throw Problems.baritoneNotInstalled();
		String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
		Instant now = Instant.now();
		JobResponse job = new JobResponse(id, "mine", "started", "Mining " + req.quantity() + " of " + req.blocks(), now, now);
		jobs.put(id, job);
		return job;
	}

	@Override
	public JobResponse startFollow(FollowRequest req) {
		if (!installed) throw Problems.baritoneNotInstalled();
		String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
		Instant now = Instant.now();
		JobResponse job = new JobResponse(id, "follow", "started", "Following target", now, now);
		jobs.put(id, job);
		return job;
	}

	@Override
	public JobResponse startExplore(ExploreRequest req) {
		if (!installed) throw Problems.baritoneNotInstalled();
		String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
		Instant now = Instant.now();
		JobResponse job = new JobResponse(id, "explore", "started", "Exploring", now, now);
		jobs.put(id, job);
		return job;
	}

	@Override
	public JobResponse startFarm(FarmRequest req) {
		if (!installed) throw Problems.baritoneNotInstalled();
		String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
		Instant now = Instant.now();
		JobResponse job = new JobResponse(id, "farm", "started", "Farming", now, now);
		jobs.put(id, job);
		return job;
	}

	@Override
	public JobResponse startBuild(BuildRequest req) {
		if (!installed) throw Problems.baritoneNotInstalled();
		String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
		Instant now = Instant.now();
		JobResponse job = new JobResponse(id, "build", "started", "Building " + req.name(), now, now);
		jobs.put(id, job);
		return job;
	}

	@Override
	public CancelResponse cancel() {
		if (!installed) throw Problems.baritoneNotInstalled();
		return new CancelResponse(true);
	}

	@Override
	public JobResponse getJob(String jobId) {
		if (!installed) throw Problems.baritoneNotInstalled();
		JobResponse job = jobs.get(jobId);
		if (job == null) {
			throw Problems.jobNotFound(jobId);
		}
		return job;
	}

	@Override
	public Map<String, SettingInfo> getSettings() {
		if (!installed) throw Problems.baritoneNotInstalled();
		return settings;
	}

	@Override
	public Map<String, Object> patchSettings(Map<String, Object> patch) {
		if (!installed) throw Problems.baritoneNotInstalled();
		Map<String, Object> updated = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : patch.entrySet()) {
			SettingInfo existing = settings.get(entry.getKey());
			if (existing == null) {
				throw Problems.badRequest("Unknown Baritone setting: " + entry.getKey());
			}
			SettingInfo newInfo = new SettingInfo(existing.name(), existing.type(), existing.defaultValue(), entry.getValue());
			settings.put(existing.name(), newInfo);
			updated.put(existing.name(), entry.getValue());
		}
		return updated;
	}

	@Override
	public CommandResponse executeCommand(String command) {
		if (!installed) throw Problems.baritoneNotInstalled();
		executedCommands.add(command);
		return new CommandResponse(true, command);
	}
}
