package dev.advik.mcdt.game.raycast;

import dev.advik.mcdt.api.v1.dto.RaycastDto;
import dev.advik.mcdt.game.serializers.BlockStateSerializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

public final class RaycastUtil {
	private RaycastUtil() {}

	public static RaycastDto fromHitResult(HitResult hit, ClientLevel level) {
		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			return new RaycastDto("miss", new double[]{0, 0, 0}, 0, null, null);
		}

		Vec3 pos = hit.getLocation();
		double[] hitPos = new double[]{pos.x, pos.y, pos.z};

		if (hit instanceof BlockHitResult bhr) {
			int[] blockPos = new int[]{bhr.getBlockPos().getX(), bhr.getBlockPos().getY(), bhr.getBlockPos().getZ()};
			String face = bhr.getDirection().getName();
			String state = BlockStateSerializer.toString(level.getBlockState(bhr.getBlockPos()));
			boolean inside = bhr.isInside();

			double distance = Minecraft.getInstance().player != null
					? Minecraft.getInstance().player.getEyePosition().distanceTo(pos)
					: 0.0;

			return new RaycastDto(
					"block",
					hitPos,
					distance,
					new RaycastDto.BlockHit(blockPos, face, state, inside),
					null
			);
		} else if (hit instanceof EntityHitResult ehr) {
			Entity e = ehr.getEntity();
			int id = e.getId();
			String uuid = e.getStringUUID();
			String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
			String name = e.getName().getString();

			double distance = Minecraft.getInstance().player != null
					? Minecraft.getInstance().player.getEyePosition().distanceTo(pos)
					: 0.0;

			return new RaycastDto(
					"entity",
					hitPos,
					distance,
					null,
					new RaycastDto.EntityHit(id, uuid, type, name)
			);
		}

		return new RaycastDto("miss", hitPos, 0, null, null);
	}

	public static RaycastDto performRaycast(
			ClientLevel level,
			Entity sourceEntity,
			double[] from,
			double[] direction,
			Double maxDistance,
			Boolean includeFluids,
			Boolean includeEntities
	) {
		Vec3 startVec;
		if (from != null && from.length >= 3) {
			startVec = new Vec3(from[0], from[1], from[2]);
		} else if (sourceEntity != null) {
			startVec = sourceEntity.getEyePosition();
		} else {
			startVec = Vec3.ZERO;
		}

		double dist = maxDistance != null ? Math.min(128.0, Math.max(0.1, maxDistance)) : 20.0;

		Vec3 lookDir;
		if (direction != null && direction.length >= 3) {
			Vec3 dir = new Vec3(direction[0], direction[1], direction[2]);
			lookDir = dir.lengthSqr() > 1e-6 ? dir.normalize() : new Vec3(0, 0, 1);
		} else if (sourceEntity != null) {
			lookDir = sourceEntity.getViewVector(1.0f);
		} else {
			lookDir = new Vec3(0, 0, 1);
		}

		Vec3 endVec = startVec.add(lookDir.scale(dist));

		ClipContext.Fluid fluidMode = Boolean.TRUE.equals(includeFluids) ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
		BlockHitResult bhr = level.clip(new ClipContext(startVec, endVec, ClipContext.Block.OUTLINE, fluidMode, sourceEntity));

		double blockDist = bhr.getType() != HitResult.Type.MISS ? startVec.distanceTo(bhr.getLocation()) : dist;

		EntityHitResult ehr = null;
		if (!Boolean.FALSE.equals(includeEntities)) {
			AABB searchBox = new AABB(startVec, endVec).inflate(1.0);
			ehr = ProjectileUtil.getEntityHitResult(
					level,
					sourceEntity,
					startVec,
					endVec,
					searchBox,
					e -> !e.isSpectator() && e.isPickable()
			);
		}

		if (ehr != null && startVec.distanceTo(ehr.getLocation()) < blockDist) {
			Vec3 pos = ehr.getLocation();
			Entity e = ehr.getEntity();
			return new RaycastDto(
					"entity",
					new double[]{pos.x, pos.y, pos.z},
					startVec.distanceTo(pos),
					null,
					new RaycastDto.EntityHit(
							e.getId(),
							e.getStringUUID(),
							net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
							e.getName().getString()
					)
			);
		}

		if (bhr.getType() != HitResult.Type.MISS) {
			Vec3 pos = bhr.getLocation();
			return new RaycastDto(
					"block",
					new double[]{pos.x, pos.y, pos.z},
					startVec.distanceTo(pos),
					new RaycastDto.BlockHit(
							new int[]{bhr.getBlockPos().getX(), bhr.getBlockPos().getY(), bhr.getBlockPos().getZ()},
							bhr.getDirection().getName(),
							BlockStateSerializer.toString(level.getBlockState(bhr.getBlockPos())),
							bhr.isInside()
					),
					null
			);
		}

		return new RaycastDto("miss", new double[]{endVec.x, endVec.y, endVec.z}, dist, null, null);
	}
}
