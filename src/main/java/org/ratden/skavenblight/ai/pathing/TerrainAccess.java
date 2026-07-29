package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Abstraction over "can I ask about this block" so that the same TerrainEvaluator /
 * SiegeProjectManager logic can run against either:
 *
 *  - a {@link LiveTerrainAccess} wrapping the real ServerLevel (only ever safe to use
 *    from the main server thread), or
 *  - a {@link TerrainSnapshot} captured ahead of time on the main thread (safe to read
 *    from any thread, including the flow field's background calculation thread).
 *
 * Implementations are responsible for being safe on whatever thread they're documented
 * for. Callers are responsible for never handing a LiveTerrainAccess across threads -
 * only a TerrainSnapshot should ever cross into the background calculation.
 */
public interface TerrainAccess {

    BlockState getBlockState(BlockPos pos);

    /**
     * For LiveTerrainAccess this mirrors ServerLevel#isLoaded. For a TerrainSnapshot this
     * means "this position was captured" - positions outside the snapshot's captured
     * region (e.g. beyond its vertical window) report false here rather than throwing,
     * which the calculation treats the same as out-of-bounds/unloaded terrain.
     */
    boolean isLoaded(BlockPos pos);

    boolean isOutsideBuildHeight(BlockPos pos);

    /** Level-dependent occlusion check (BlockState#isSolidRender needs a BlockGetter). */
    boolean isSolidRender(BlockPos pos);

    /** Level-dependent mining hardness (BlockState#getDestroySpeed needs a BlockGetter). */
    float getDestroySpeed(BlockPos pos);
}
