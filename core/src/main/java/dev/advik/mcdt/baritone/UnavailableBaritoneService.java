package dev.advik.mcdt.baritone;

import dev.advik.mcdt.api.v1.dto.BaritoneDto.*;
import dev.advik.mcdt.http.Problems;

import java.util.Map;

/**
 * Fallback implementation used when Baritone is not installed or disabled.
 * Every operation throws HTTP 501 Not Implemented with RFC 9457 problem JSON.
 */
public final class UnavailableBaritoneService implements BaritoneService {
	@Override
	public boolean isInstalled() {
		return false;
	}

	@Override
	public StatusResponse getStatus() {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public PathResponse getPath() {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public JobResponse startGoto(GotoRequest req) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public JobResponse startMine(MineRequest req) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public JobResponse startFollow(FollowRequest req) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public JobResponse startExplore(ExploreRequest req) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public JobResponse startFarm(FarmRequest req) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public JobResponse startBuild(BuildRequest req) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public CancelResponse cancel() {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public JobResponse getJob(String jobId) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public Map<String, SettingInfo> getSettings() {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public Map<String, Object> patchSettings(Map<String, Object> patch) {
		throw Problems.baritoneNotInstalled();
	}

	@Override
	public CommandResponse executeCommand(String command) {
		throw Problems.baritoneNotInstalled();
	}
}
