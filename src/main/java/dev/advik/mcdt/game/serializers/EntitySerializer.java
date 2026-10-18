package dev.advik.mcdt.game.serializers;

import dev.advik.mcdt.api.v1.dto.EntitiesDto.BoundingBox;
import dev.advik.mcdt.api.v1.dto.EntitiesDto.EntityInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EntitySerializer {
	private EntitySerializer() {}

	public static EntityInfo toEntityInfo(Entity entity, Vec3 origin) {
		int id = entity.getId();
		String uuid = entity.getStringUUID();
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		double[] pos = new double[]{entity.getX(), entity.getY(), entity.getZ()};
		Vec3 vel = entity.getDeltaMovement();
		double[] velocity = new double[]{vel.x, vel.y, vel.z};
		float yaw = entity.getYRot();
		float pitch = entity.getXRot();
		double distance = origin != null ? entity.position().distanceTo(origin) : 0.0;
		boolean alive = entity.isAlive();

		Float health = null;
		Float maxHealth = null;
		if (entity instanceof LivingEntity living) {
			health = living.getHealth();
			maxHealth = living.getMaxHealth();
		}

		String customName = entity.getCustomName() != null ? entity.getCustomName().getString() : null;

		AABB bb = entity.getBoundingBox();
		BoundingBox boundingBox = new BoundingBox(
				new double[]{bb.minX, bb.minY, bb.minZ},
				new double[]{bb.maxX, bb.maxY, bb.maxZ}
		);

		return new EntityInfo(
				id,
				uuid,
				type,
				pos,
				velocity,
				yaw,
				pitch,
				distance,
				alive,
				health,
				maxHealth,
				customName,
				boundingBox
		);
	}
}
