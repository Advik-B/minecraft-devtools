package dev.advik.mcdt.baritone;

import dev.advik.mcdt.api.v1.dto.BaritoneDto.*;

import java.util.Map;

/**
 * Interface abstracting high-level Baritone operations, decoupling REST handlers from Baritone classes.
 */
public interface BaritoneService {
	boolean isInstalled();

	StatusResponse getStatus();

	PathResponse getPath();

	JobResponse startGoto(GotoRequest req);

	JobResponse startMine(MineRequest req);

	JobResponse startFollow(FollowRequest req);

	JobResponse startExplore(ExploreRequest req);

	JobResponse startFarm(FarmRequest req);

	JobResponse startBuild(BuildRequest req);

	CancelResponse cancel();

	JobResponse getJob(String jobId);

	Map<String, SettingInfo> getSettings();

	Map<String, Object> patchSettings(Map<String, Object> patch);

	CommandResponse executeCommand(String command);
}
