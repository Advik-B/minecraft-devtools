package dev.advik.mcdt.game.viewer;

import dev.advik.mcdt.game.McRegistries;
import dev.advik.mcdt.api.v1.dto.BlockModelsDto;
import dev.advik.mcdt.game.McCompat;
import dev.advik.mcdt.game.serializers.BlockStateSerializer;
import dev.advik.mcdt.world.stream.ViewData;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//? if >=26.1 {
/*import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.CardinalLighting;
*///?} else if >=1.21.11 {
/*import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.BlockAndTintGetter;
*///?} else {
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.BlockAndTintGetter;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
//? if >=1.21 {
import net.minecraft.util.RandomSource;
//?}
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
//? if >=1.21 {
import net.minecraft.world.entity.vehicle.VehicleEntity;
//?} else {
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
*///?}
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
				McCompat.location(level.dimension()).toString(),
				//? if >=1.21.2 {
				level.getMinY(),
				//?} else if >=1.21 {
				/*level.getMinBuildHeight(),
				*///?} else {
				/*0,
				*///?}
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
			LevelChunkSection section = sections[i];
			//? if >=1.21 {
			int sectionY = chunk.getSectionYFromSectionIndex(i);
			boolean empty = section == null || section.hasOnlyAir();
			//?} else {
			/*// Before 1.17 the world is 0-255: section i is at section y i.
			int sectionY = i;
			boolean empty = LevelChunkSection.isEmpty(section);
			*///?}
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
					//? if >=1.21 {
					Biome biome = level.getNoiseBiome((baseX + cx) >> 2, y >> 2, (baseZ + cz) >> 2).value();
					//?} else {
					/*Biome biome = level.getNoiseBiome((baseX + cx) >> 2, y >> 2, (baseZ + cz) >> 2);
					*///?}
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
		return filledWith(data, 15) ? null : data.getData().clone();
	}

	private static byte[] blockLight(LayerLightEventListener listener, SectionPos sectionPos) {
		DataLayer data = listener.getDataLayerData(sectionPos);
		if (data == null || filledWith(data, 0)) return null;
		return data.getData().clone();
	}

	/** Whether every light value in the layer is {@code value}. */
	private static boolean filledWith(DataLayer data, int value) {
		//? if >=1.21 {
		return data.isDefinitelyFilledWith(value);
		//?} else {
		/*if (data.isEmpty()) {
			return value == 0;
		}
		byte packed = (byte) (value | value << 4);
		for (byte b : data.getData()) {
			if (b != packed) {
				return false;
			}
		}
		return true;
		*///?}
	}

	public static ViewData.Pose pose() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) return null;
		return new ViewData.Pose(
				player.getX(), player.getY(), player.getZ(),
				McCompat.yRot(player), McCompat.xRot(player),
				McCompat.dayTime(level),
				skyBrightness(level)
		);
	}

	/** Sky brightness from 0.2 (night, storm) to 1.0 (clear day), as the renderer scales it. */
	private static float skyBrightness(ClientLevel level) {
		//? if >=1.21.11 {
		/*// Only the 0-11 darkness level is left; map it onto the old 0.2-1.0 scale.
		return (1.0f - level.getSkyDarken() / 11.0f) * 0.8f + 0.2f;
		*///?} else {
		return level.getSkyDarken(1.0f);
		//?}
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
			String name = entity instanceof Player p ? McCompat.profileName(p.getGameProfile())
					: entity.hasCustomName() && entity.getCustomName() != null ? entity.getCustomName().getString() : null;
			result.add(new ViewData.Entity(
					entity.getId(),
					McRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
					category(entity),
					entity.getX(), entity.getY(), entity.getZ(),
					McCompat.yRot(entity),
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
		//? if >=1.21 {
		if (entity instanceof Projectile || entity instanceof VehicleEntity) return 5;
		//?} else {
		/*if (entity instanceof Projectile || entity instanceof AbstractMinecart || entity instanceof Boat) return 5;
		*///?}
		MobCategory category = entity.getType().getCategory();
		if (category == MobCategory.MONSTER) return 2;
		if (category == MobCategory.MISC) return 0;
		return 3;
	}

	public static BlockModelsDto.Response blockModels(List<Integer> stateIds) {
		Minecraft client = Minecraft.getInstance();
		//? if >=26.1 {
		/*var modelSet = client.getModelManager().getBlockStateModelSet();
		var fluidModels = client.getModelManager().getFluidStateModelSet();
		*///?} else {
		var shaper = client.getModelManager().getBlockModelShaper();
		//?}
		BlockColors colors = client.getBlockColors();
		TextureAtlas atlas = (TextureAtlas) client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		//? if >=1.21 {
		RandomSource random = RandomSource.create();
		//?} else {
		/*java.util.Random random = new java.util.Random();
		*///?}
		List<BlockModelsDto.Model> models = new ArrayList<>();

		for (Integer boxed : stateIds) {
			if (boxed == null) continue;
			int id = boxed;
			BlockState state = Block.stateById(id);
			if (state == null) continue;
			String stateStr = BlockStateSerializer.toString(state);
			int blockId = McRegistries.BLOCK.getId(state.getBlock());
			if (state.isAir()) {
				models.add(new BlockModelsDto.Model(id, stateStr, blockId, true, "solid", 0, false, false, false, 0, null, List.of()));
				continue;
			}

			int occlusion = 0;
			if (state.canOcclude()) {
				for (Direction dir : Direction.values()) {
					//? if >=1.21.2 {
					net.minecraft.world.phys.shapes.VoxelShape face = state.getFaceOcclusionShape(dir);
					//?} else {
					/*net.minecraft.world.phys.shapes.VoxelShape face = state.getFaceOcclusionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO, dir);
					*///?}
					if (Block.isFaceFull(face, dir)) {
						occlusion |= 1 << dir.get3DDataValue();
					}
				}
			}

			List<BlockModelsDto.Quad> quads = new ArrayList<>();
			//? if >=26.1 {
			/*BlockStateModel model = modelSet.get(state);
			random.setSeed(42L);
			List<BlockStateModelPart> parts = new ArrayList<>();
			model.collectParts(random, parts);
			boolean ambientOcclusion = false;
			// The chunk layer now comes from the quads' materials: the most transparent one wins.
			ChunkSectionLayer layer = ChunkSectionLayer.SOLID;
			for (BlockStateModelPart part : parts) {
				for (Direction cull : Direction.values()) {
					layer = addQuads(quads, part.getQuads(cull), cull, state, colors, layer);
				}
				layer = addQuads(quads, part.getQuads(null), null, state, colors, layer);
				ambientOcclusion |= part.useAmbientOcclusion();
			}
			String blockLayer = layerName(layer);
			*///?} else if >=1.21.11 {
			/*BlockStateModel model = shaper.getBlockModel(state);
			random.setSeed(42L);
			List<BlockModelPart> parts = model.collectParts(random);
			boolean ambientOcclusion = false;
			for (BlockModelPart part : parts) {
				for (Direction cull : Direction.values()) {
					addQuads(quads, part.getQuads(cull), cull, state, colors);
				}
				addQuads(quads, part.getQuads(null), null, state, colors);
				ambientOcclusion |= part.useAmbientOcclusion();
			}
			String blockLayer = layerName(ItemBlockRenderTypes.getChunkRenderType(state));
			*///?} else {
			BakedModel model = shaper.getBlockModel(state);
			for (Direction cull : Direction.values()) {
				random.setSeed(42L);
				addQuads(quads, model.getQuads(state, cull, random), cull, state, colors);
			}
			random.setSeed(42L);
			addQuads(quads, model.getQuads(state, null, random), null, state, colors);
			boolean ambientOcclusion = model.useAmbientOcclusion();
			String blockLayer = layerName(ItemBlockRenderTypes.getChunkRenderType(state));
			//?}

			BlockModelsDto.Fluid fluid = null;
			FluidState fluidState = state.getFluidState();
			if (!fluidState.isEmpty()) {
				boolean water = fluidState.is(FluidTags.WATER);
				String prefix = water ? "block/water" : "block/lava";
				fluid = new BlockModelsDto.Fluid(
						water ? "water" : "lava",
						fluidState.getOwnHeight(),
						fluidState.hasProperty(FlowingFluid.FALLING) && fluidState.getValue(FlowingFluid.FALLING),
						//? if >=26.1 {
						/*layerName(fluidModels.get(fluidState).layer()),
						*///?} else {
						layerName(ItemBlockRenderTypes.getRenderLayer(fluidState)),
						//?}
						rect(atlas.getSprite(McCompat.id("minecraft", prefix + "_still"))),
						rect(atlas.getSprite(McCompat.id("minecraft", prefix + "_flow")))
				);
			}

			models.add(new BlockModelsDto.Model(
					id,
					stateStr,
					blockId,
					false,
					blockLayer,
					occlusion,
					state.skipRendering(state, Direction.UP),
					state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
					ambientOcclusion && state.getLightEmission() == 0,
					state.getLightEmission(),
					fluid,
					quads
			));
		}
		return new BlockModelsDto.Response(models);
	}

	//? if >=26.1 {
	/*/^* Adds the quads and returns the most transparent chunk layer seen so far (starting from {@code layer}). ^/
	private static ChunkSectionLayer addQuads(List<BlockModelsDto.Quad> out, List<BakedQuad> quads, Direction cull, BlockState state, BlockColors colors, ChunkSectionLayer layer) {
	*///?} else {
	private static void addQuads(List<BlockModelsDto.Quad> out, List<BakedQuad> quads, Direction cull, BlockState state, BlockColors colors) {
	//?}
		for (BakedQuad quad : quads) {
			float[] pos = new float[12];
			float[] uv = new float[8];
			//? if >=26.1 {
			/*for (int i = 0; i < 4; i++) {
				var p = quad.position(i);
				pos[i * 3] = p.x();
				pos[i * 3 + 1] = p.y();
				pos[i * 3 + 2] = p.z();
				uv[i * 2] = UVPair.unpackU(quad.packedUV(i));
				uv[i * 2 + 1] = UVPair.unpackV(quad.packedUV(i));
			}
			var material = quad.materialInfo();
			boolean tinted = material.isTinted();
			int tintIndex = material.tintIndex();
			int direction = quad.direction().get3DDataValue();
			// A model element with "shade": false now gets a shade direction override instead of a flag.
			boolean shade = material.shadeDirectionOverride() == null;
			if (material.layer().ordinal() > layer.ordinal()) {
				layer = material.layer();
			}
			*///?} else if >=1.21.11 {
			/*for (int i = 0; i < 4; i++) {
				var p = quad.position(i);
				pos[i * 3] = p.x();
				pos[i * 3 + 1] = p.y();
				pos[i * 3 + 2] = p.z();
				uv[i * 2] = UVPair.unpackU(quad.packedUV(i));
				uv[i * 2 + 1] = UVPair.unpackV(quad.packedUV(i));
			}
			boolean tinted = quad.isTinted();
			int tintIndex = quad.tintIndex();
			int direction = quad.direction().get3DDataValue();
			boolean shade = quad.shade();
			*///?} else {
			int[] v = quad.getVertices();
			int stride = v.length / 4;
			for (int i = 0; i < 4; i++) {
				int base = i * stride;
				pos[i * 3] = Float.intBitsToFloat(v[base]);
				pos[i * 3 + 1] = Float.intBitsToFloat(v[base + 1]);
				pos[i * 3 + 2] = Float.intBitsToFloat(v[base + 2]);
				uv[i * 2] = Float.intBitsToFloat(v[base + 4]);
				uv[i * 2 + 1] = Float.intBitsToFloat(v[base + 5]);
			}
			boolean tinted = quad.isTinted();
			int tintIndex = quad.getTintIndex();
			int direction = quad.getDirection().get3DDataValue();
			boolean shade = quad.isShade();
			//?}
			int tint = TINT_NONE;
			int color = 0;
			if (tinted) {
				int c = tintColor(colors, state, tintIndex);
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
					direction,
					cull != null ? cull.get3DDataValue() : -1,
					tint,
					color,
					shade
			));
		}
		//? if >=26.1 {
		/*return layer;
		*///?}
	}

	/** The color a block's tint index resolves to at the probe position (0xFFFFFF: untinted). */
	private static int tintColor(BlockColors colors, BlockState state, int tintIndex) {
		//? if >=26.1 {
		/*BlockTintSource source = colors.getTintSource(state, tintIndex);
		return source != null ? source.colorInWorld(state, TintProbe.INSTANCE, BlockPos.ZERO) & 0xFFFFFF : 0xFFFFFF;
		*///?} else {
		return colors.getColor(state, TintProbe.INSTANCE, BlockPos.ZERO, tintIndex) & 0xFFFFFF;
		//?}
	}

	private static float[] rect(TextureAtlasSprite sprite) {
		return new float[] {sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()};
	}

	//? if >=26.1 {
	/*/^* Tripwire has no layer of its own any more; it renders as cutout. ^/
	private static String layerName(ChunkSectionLayer layer) {
		return switch (layer) {
			case TRANSLUCENT -> "translucent";
			case CUTOUT -> "cutout";
			default -> "solid";
		};
	}
	*///?} else if >=1.21.11 {
	/*/^* Chunk layers lost the cutout/cutout-mipped split; mipped cutout is now just "cutout". ^/
	private static String layerName(ChunkSectionLayer layer) {
		return switch (layer) {
			case TRANSLUCENT -> "translucent";
			case CUTOUT -> "cutout";
			case TRIPWIRE -> "tripwire";
			default -> "solid";
		};
	}
	*///?} else {
	private static String layerName(RenderType type) {
		if (type == RenderType.translucent()) return "translucent";
		if (type == RenderType.cutout()) return "cutout";
		if (type == RenderType.cutoutMipped()) return "cutout_mipped";
		if (type == RenderType.tripwire()) return "tripwire";
		return "solid";
	}
	//?}

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

		//? if >=26.1 {
		/*@Override
		public CardinalLighting cardinalLighting() {
			return CardinalLighting.DEFAULT;
		}
		*///?} else {
		@Override
		public float getShade(Direction direction, boolean shade) {
			return 1.0f;
		}
		//?}

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

		public int getHeight() {
			return 384;
		}

		//? if >=1.21.2 {
		public int getMinY() {
		//?} else {
		/*public int getMinBuildHeight() {
		*///?}
			return -64;
		}
	}
}
