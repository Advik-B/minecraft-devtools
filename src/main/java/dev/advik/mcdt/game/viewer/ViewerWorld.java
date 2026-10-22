package dev.advik.mcdt.game.viewer;

import dev.advik.mcdt.api.v1.dto.BlockModelsDto;
import dev.advik.mcdt.game.serializers.BlockStateSerializer;
import dev.advik.mcdt.world.stream.ViewData;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads world state for the 3D viewer. Every method must run on the client thread.
 */
public final class ViewerWorld {
	private static final int TINT_NONE = 0;
	private static final int TINT_GRASS = 1;
	private static final int TINT_FOLIAGE = 2;
	private static final int TINT_WATER = 3;
	private static final int TINT_CONSTANT = 4;

	private static final int SENTINEL_GRASS = 0x0F0001;
	private static final int SENTINEL_FOLIAGE = 0x0F0002;
	private static final int SENTINEL_WATER = 0x0F0003;


	private ViewerWorld() {}

	public static ViewData.Level level() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return null;
		return new ViewData.Level(
				System.identityHashCode(level),
				level.dimension().location().toString(),
				level.getMinY(),
				level.getHeight(),
				level.dimensionType().hasSkyLight()
		);
	}

	public static boolean isChunkLoaded(int chunkX, int chunkZ) {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && level.getChunkSource().getChunk(chunkX, chunkZ, false) != null;
	}

	public static ViewData.Chunk chunk(int chunkX, int chunkZ, Set<Integer> sectionYs) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return null;
		LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
		if (chunk == null) return null;

		boolean hasSky = level.dimensionType().hasSkyLight();
		LevelLightEngine light = level.getLightEngine();
		LayerLightEventListener skyListener = light.getLayerListener(LightLayer.SKY);
		LayerLightEventListener blockListener = light.getLayerListener(LightLayer.BLOCK);
		Reference2IntOpenHashMap<BlockState> paletteMap = new Reference2IntOpenHashMap<>();
		paletteMap.defaultReturnValue(-1);

		LevelChunkSection[] sections = chunk.getSections();
		List<ViewData.Section> result = new ArrayList<>();
		for (int i = 0; i < sections.length; i++) {
			int sectionY = chunk.getSectionYFromSectionIndex(i);
			LevelChunkSection section = sections[i];
			boolean empty = section == null || section.hasOnlyAir();
			if (sectionYs == null ? empty : !sectionYs.contains(sectionY)) continue;

			SectionPos sectionPos = SectionPos.of(chunkX, sectionY, chunkZ);
			byte[] sky = hasSky ? skyLight(skyListener, sectionPos) : null;
			byte[] block = blockLight(blockListener, sectionPos);
			if (empty) {
				result.add(new ViewData.Section(sectionY, new int[] {0}, null, sky, block));
				continue;
			}

			paletteMap.clear();
			SectionEncoder encoder = new SectionEncoder(paletteMap);
			// PalettedContainer.getAll only visits distinct values, so walk coordinates in y<<8 | z<<4 | x order.
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						encoder.accept(section.getBlockState(x, y, z));
					}
				}
			}
			int[] ids = encoder.palette.stream().mapToInt(Integer::intValue).toArray();
			short[] indices = encoder.indices;
			result.add(new ViewData.Section(sectionY, ids, ids.length > 1 ? indices : null, sky, block));
		}

		int[] grass = null;
		int[] foliage = null;
		int[] water = null;
		if (sectionYs == null) {
			grass = new int[256];
			foliage = new int[256];
			water = new int[256];
			// Raw biome colors at the biome grid's 4×4 resolution; the viewer blends them like the game's biome blend.
			// Sampling BiomeColors per column instead costs ~5 ms per chunk.
			int baseX = chunkX << 4;
			int baseZ = chunkZ << 4;
			for (int qz = 0; qz < 4; qz++) {
				for (int qx = 0; qx < 4; qx++) {
					int cx = qx * 4 + 2;
					int cz = qz * 4 + 2;
					int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
					Biome biome = level.getNoiseBiome((baseX + cx) >> 2, y >> 2, (baseZ + cz) >> 2).value();
					int g = biome.getGrassColor(baseX + cx, baseZ + cz);
					int f = biome.getFoliageColor();
					int w = biome.getWaterColor();
					for (int dz = 0; dz < 4; dz++) {
						for (int dx = 0; dx < 4; dx++) {
							int i = (qz * 4 + dz) << 4 | (qx * 4 + dx);
							grass[i] = g;
							foliage[i] = f;
							water[i] = w;
						}
					}
				}
			}
		}
		return new ViewData.Chunk(chunkX, chunkZ, sectionYs == null, result, grass, foliage, water);
	}

	/** Collects a section's states into a local palette of global ids plus 4096 indices. */
	private static final class SectionEncoder implements java.util.function.Consumer<BlockState> {
		final Reference2IntOpenHashMap<BlockState> paletteMap;
		final List<Integer> palette = new ArrayList<>();
		final short[] indices = new short[4096];
		int idx;
		BlockState last;
		int lastIndex = -1;

		SectionEncoder(Reference2IntOpenHashMap<BlockState> paletteMap) {
			this.paletteMap = paletteMap;
		}

		@Override
		public void accept(BlockState state) {
			if (state != last) {
				// Runs of one state are the common case; skip the map lookup for them.
				last = state;
				lastIndex = paletteMap.getInt(state);
				if (lastIndex < 0) {
					lastIndex = palette.size();
					paletteMap.put(state, lastIndex);
					palette.add(Block.getId(state));
				}
			}
			indices[idx++] = (short) lastIndex;
		}
	}

	private static byte[] skyLight(LayerLightEventListener listener, SectionPos sectionPos) {
		DataLayer data = listener.getDataLayerData(sectionPos);
		if (data == null) {
			// No stored layer: sample through the engine, which looks upward for open sky.
			DataLayer sampled = new DataLayer();
			BlockPos origin = sectionPos.origin();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			boolean allFull = true;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int v = listener.getLightValue(pos.setWithOffset(origin, x, y, z));
						sampled.set(x, y, z, v);
						allFull &= v == 15;
					}
				}
			}
			return allFull ? null : sampled.getData();
		}
		return data.isDefinitelyFilledWith(15) ? null : data.getData().clone();
	}

	private static byte[] blockLight(LayerLightEventListener listener, SectionPos sectionPos) {
		DataLayer data = listener.getDataLayerData(sectionPos);
		if (data == null || data.isDefinitelyFilledWith(0)) return null;
		return data.getData().clone();
	}

	public static ViewData.Pose pose() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) return null;
		return new ViewData.Pose(
				player.getX(), player.getY(), player.getZ(),
				player.getYRot(), player.getXRot(),
				level.getDayTime(),
				level.getSkyDarken(1.0f)
		);
	}

	public static List<ViewData.Entity> entities(double centerX, double centerZ, double radius, int limit) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		List<ViewData.Entity> result = new ArrayList<>();
		if (level == null) return result;
		double r2 = radius * radius;
		for (Entity entity : level.entitiesForRendering()) {
			if (result.size() >= limit) break;
			double dx = entity.getX() - centerX;
			double dz = entity.getZ() - centerZ;
			if (dx * dx + dz * dz > r2) continue;
			String name = entity instanceof Player p ? p.getGameProfile().getName()
					: entity.hasCustomName() && entity.getCustomName() != null ? entity.getCustomName().getString() : null;
			result.add(new ViewData.Entity(
					entity.getId(),
					BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
					category(entity),
					entity.getX(), entity.getY(), entity.getZ(),
					entity.getYRot(),
					entity.getBbWidth(), entity.getBbHeight(),
					entity == client.player,
					name
			));
		}
		return result;
	}

	private static int category(Entity entity) {
		if (entity instanceof Player) return 1;
		if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) return 4;
		if (entity instanceof Projectile || entity instanceof VehicleEntity) return 5;
		MobCategory category = entity.getType().getCategory();
		if (category == MobCategory.MONSTER) return 2;
		if (category == MobCategory.MISC) return 0;
		return 3;
	}

	public static BlockModelsDto.Response blockModels(List<Integer> stateIds) {
		Minecraft client = Minecraft.getInstance();
		var shaper = client.getModelManager().getBlockModelShaper();
		BlockColors colors = client.getBlockColors();
		var atlas = client.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
		RandomSource random = RandomSource.create();
		List<BlockModelsDto.Model> models = new ArrayList<>();

		for (Integer boxed : stateIds) {
			if (boxed == null) continue;
			int id = boxed;
			BlockState state = Block.stateById(id);
			if (state == null) continue;
			String stateStr = BlockStateSerializer.toString(state);
			int blockId = BuiltInRegistries.BLOCK.getId(state.getBlock());
			if (state.isAir()) {
				models.add(new BlockModelsDto.Model(id, stateStr, blockId, true, "solid", 0, false, false, false, 0, null, List.of()));
				continue;
			}

			int occlusion = 0;
			if (state.canOcclude()) {
				for (Direction dir : Direction.values()) {
					if (Block.isFaceFull(state.getFaceOcclusionShape(dir), dir)) {
						occlusion |= 1 << dir.get3DDataValue();
					}
				}
			}

			BakedModel model = shaper.getBlockModel(state);
			List<BlockModelsDto.Quad> quads = new ArrayList<>();
			for (Direction cull : Direction.values()) {
				random.setSeed(42L);
				addQuads(quads, model.getQuads(state, cull, random), cull, state, colors);
			}
			random.setSeed(42L);
			addQuads(quads, model.getQuads(state, null, random), null, state, colors);

			BlockModelsDto.Fluid fluid = null;
			FluidState fluidState = state.getFluidState();
			if (!fluidState.isEmpty()) {
				boolean water = fluidState.is(FluidTags.WATER);
				String prefix = water ? "block/water" : "block/lava";
				fluid = new BlockModelsDto.Fluid(
						water ? "water" : "lava",
						fluidState.getOwnHeight(),
						fluidState.hasProperty(FlowingFluid.FALLING) && fluidState.getValue(FlowingFluid.FALLING),
						layerName(ItemBlockRenderTypes.getRenderLayer(fluidState)),
						rect(atlas.apply(ResourceLocation.withDefaultNamespace(prefix + "_still"))),
						rect(atlas.apply(ResourceLocation.withDefaultNamespace(prefix + "_flow")))
				);
			}

			models.add(new BlockModelsDto.Model(
					id,
					stateStr,
					blockId,
					false,
					layerName(ItemBlockRenderTypes.getChunkRenderType(state)),
					occlusion,
					state.skipRendering(state, Direction.UP),
					state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
					model.useAmbientOcclusion() && state.getLightEmission() == 0,
					state.getLightEmission(),
					fluid,
					quads
			));
		}
		return new BlockModelsDto.Response(models);
	}

	private static void addQuads(List<BlockModelsDto.Quad> out, List<BakedQuad> quads, Direction cull, BlockState state, BlockColors colors) {
		for (BakedQuad quad : quads) {
			int[] v = quad.getVertices();
			int stride = v.length / 4;
			float[] pos = new float[12];
			float[] uv = new float[8];
			for (int i = 0; i < 4; i++) {
				int base = i * stride;
				pos[i * 3] = Float.intBitsToFloat(v[base]);
				pos[i * 3 + 1] = Float.intBitsToFloat(v[base + 1]);
				pos[i * 3 + 2] = Float.intBitsToFloat(v[base + 2]);
				uv[i * 2] = Float.intBitsToFloat(v[base + 4]);
				uv[i * 2 + 1] = Float.intBitsToFloat(v[base + 5]);
			}
			int tint = TINT_NONE;
			int color = 0;
			if (quad.isTinted()) {
				int c = colors.getColor(state, TintProbe.INSTANCE, BlockPos.ZERO, quad.getTintIndex()) & 0xFFFFFF;
				switch (c) {
					case SENTINEL_GRASS -> tint = TINT_GRASS;
					case SENTINEL_FOLIAGE -> tint = TINT_FOLIAGE;
					case SENTINEL_WATER -> tint = TINT_WATER;
					case 0xFFFFFF -> tint = TINT_NONE;
					default -> {
						tint = TINT_CONSTANT;
						color = c;
					}
				}
			}
			out.add(new BlockModelsDto.Quad(
					pos,
					uv,
					quad.getDirection().get3DDataValue(),
					cull != null ? cull.get3DDataValue() : -1,
					tint,
					color,
					quad.isShade()
			));
		}
	}

	private static float[] rect(TextureAtlasSprite sprite) {
		return new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
	}

	private static String layerName(RenderType type) {
		if (type == RenderType.translucent()) return "translucent";
		if (type == RenderType.cutout()) return "cutout";
		if (type == RenderType.cutoutMipped()) return "cutout_mipped";
		if (type == RenderType.tripwire()) return "tripwire";
		return "solid";
	}

	/**
	 * Tint lookups return a sentinel per biome resolver, so a block color provider reveals whether it reads grass,
	 * foliage or water color, or returns a constant.
	 */
	private static final class TintProbe implements BlockAndTintGetter {
		static final TintProbe INSTANCE = new TintProbe();

		@Override
		public int getBlockTint(BlockPos pos, ColorResolver resolver) {
			if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) return SENTINEL_GRASS;
			if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) return SENTINEL_FOLIAGE;
			if (resolver == BiomeColors.WATER_COLOR_RESOLVER) return SENTINEL_WATER;
			return 0xFFFFFF;
		}

		@Override
		public float getShade(Direction direction, boolean shade) {
			return 1.0f;
		}

		@Override
		public LevelLightEngine getLightEngine() {
			throw new UnsupportedOperationException();
		}

		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Blocks.AIR.defaultBlockState().getFluidState();
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinY() {
			return -64;
		}
	}
}
