package dev.advik.mcdt.api.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Baked block geometry for the 3D viewer, keyed by global block state id. Directions use Minecraft's 3D data
 * order: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
 */
public final class BlockModelsDto {
	private BlockModelsDto() {}

	public record Request(List<Integer> ids) {}

	public record Response(List<Model> models) {}

	/**
	 * @param blockId    registry id of the block, used for same-block face culling
	 * @param layer      solid, cutout, cutout_mipped, translucent or tripwire
	 * @param occlusion  bit d set when face d fully hides the neighbor's opposite face
	 * @param selfCull   faces against another state of the same block are hidden (glass, ice)
	 * @param aoOccluder darkens neighbors for ambient occlusion (full collision cube)
	 * @param ao         model uses ambient occlusion
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Model(
			int id,
			String state,
			int blockId,
			boolean air,
			String layer,
			int occlusion,
			boolean selfCull,
			boolean aoOccluder,
			boolean ao,
			int emission,
			Fluid fluid,
			List<Quad> quads
	) {}

	/**
	 * @param pos   4 vertices × xyz in block-local units (0..1)
	 * @param uv    4 vertices × uv in atlas space
	 * @param face  direction the quad faces
	 * @param cull  direction whose neighbor can hide this quad, or -1
	 * @param tint  0 none, 1 grass, 2 foliage, 3 water, 4 constant {@code color}
	 */
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	public record Quad(float[] pos, float[] uv, int face, int cull, int tint, int color, boolean shade) {}

	/**
	 * @param type    water or lava
	 * @param height  surface height of this cell (0..1); a falling or covered fluid is drawn full height
	 * @param still   atlas rect u0,v0,u1,v1 of the still sprite
	 * @param flow    atlas rect of the flowing sprite
	 */
	public record Fluid(String type, float height, boolean falling, String layer, float[] still, float[] flow) {}
}
