package dev.advik.mcdt;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Facts about the running client that the core needs, supplied by the client entrypoint (or by tests).
 *
 * @param mcdtVersion   version of this mod
 * @param gameVersion   Minecraft version
 * @param gameDir       game directory
 * @param configDir     MCDT's config directory ({@code config/mcdt})
 * @param logDir        MCDT's log directory ({@code logs/mcdt})
 * @param userHome      home directory, for {@code ~/.mcdt/instances}
 * @param playerName    signed-in player name
 * @param baritone      Baritone status: {@code not-installed} or its version
 * @param startTime     when the client started
 */
public record McdtEnvironment(
		String mcdtVersion,
		String gameVersion,
		Path gameDir,
		Path configDir,
		Path logDir,
		Path userHome,
		String playerName,
		String baritone,
		Instant startTime
) {
	public Path instancesDir() {
		return userHome.resolve(".mcdt").resolve("instances");
	}
}
