package dev.advik.mcdt.docker;

/**
 * One MC_MODRINTH entry: {@code slug} (the newest version for the image's loader and Minecraft version)
 * or {@code slug:version} (a version number or a version id).
 */
public record ModrinthSpec(String project, String version) {
	public static ModrinthSpec parse(String spec) {
		int colon = spec.indexOf(':');
		String project = colon < 0 ? spec : spec.substring(0, colon);
		String version = colon < 0 ? null : spec.substring(colon + 1);
		if (project.isEmpty() || !project.matches("[A-Za-z0-9._-]+")) {
			throw new SetupException("MC_MODRINTH: '" + spec + "' is not a Modrinth project slug or id.");
		}
		if (version != null && version.isEmpty()) {
			throw new SetupException("MC_MODRINTH: '" + spec + "' has an empty version after ':'.");
		}
		return new ModrinthSpec(project, version);
	}

	@Override
	public String toString() {
		return version == null ? project : project + ":" + version;
	}
}
