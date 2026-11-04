package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerSkinDto(
		String playerName,
		String playerUuid,
		String model,
		String textureUrl,
		String capeUrl,
		String elytraUrl,
		boolean secure,
		String imageBase64
) {}
