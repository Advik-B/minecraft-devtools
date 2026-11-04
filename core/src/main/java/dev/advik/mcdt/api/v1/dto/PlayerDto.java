package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record PlayerDto(
		double[] pos,
		Rotation rotation,
		double[] velocity,
		float health,
		float maxHealth,
		float absorption,
		int food,
		float saturation,
		Experience experience,
		String gameMode,
		String pose,
		boolean onGround,
		boolean sneaking,
		boolean sprinting,
		boolean swimming,
		boolean sleeping,
		AirSupply air,
		List<EffectInfo> effects,
		Abilities abilities
) {
	public record Rotation(float yaw, float pitch) {}

	public record Experience(int level, float progress, int total) {}

	public record AirSupply(int current, int max) {}

	public record EffectInfo(
			String id,
			int amplifier,
			int durationTicks,
			boolean ambient,
			boolean visible
	) {}

	public record Abilities(
			boolean flying,
			boolean mayfly,
			boolean instabuild,
			boolean invulnerable
	) {}
}
