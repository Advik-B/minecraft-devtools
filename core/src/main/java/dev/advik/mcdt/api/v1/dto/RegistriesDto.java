package dev.advik.mcdt.api.v1.dto;

import java.util.List;

public final class RegistriesDto {
	private RegistriesDto() {}

	public record ListResponse(
			int count,
			List<String> registries
	) {}

	public record EntryResponse(
			String registry,
			int total,
			int offset,
			int limit,
			List<String> entries
	) {}
}
