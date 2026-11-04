package dev.advik.mcdt.api.v1.dto;

public record WorldDto(
		String dimension,
		TimeInfo time,
		WeatherInfo weather,
		String difficulty,
		boolean hardcore,
		SpawnInfo spawn,
		int loadedChunks,
		int entityCount
) {
	public record TimeInfo(
			long dayTime,
			long gameTime,
			long day,
			long timeOfDay
	) {}

	public record WeatherInfo(
			boolean raining,
			boolean thundering,
			float rainLevel,
			float thunderLevel
	) {}

	public record SpawnInfo(int x, int y, int z) {}
}
