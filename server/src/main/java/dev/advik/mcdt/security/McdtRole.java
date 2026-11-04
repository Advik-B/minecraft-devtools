package dev.advik.mcdt.security;

import dev.advik.mcdt.config.Permission;
import io.javalin.security.RouteRole;

/**
 * Route roles attached to endpoints for permission enforcement.
 */
public enum McdtRole implements RouteRole {
	READ(Permission.READ),
	ACT(Permission.ACT),
	REFLECT_WRITE(Permission.REFLECT_WRITE);

	private final Permission permission;

	McdtRole(Permission permission) {
		this.permission = permission;
	}

	public Permission permission() {
		return permission;
	}
}
