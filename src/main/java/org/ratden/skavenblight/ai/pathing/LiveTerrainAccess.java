package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wraps a live ServerLevel. This is a thin passthrough with no thread-safety of its own -
 * ONLY ever construct and use this on the main server thread. It exists so real-time,
 * always-current queries (e.g. "has this rat's build actually completed yet?") can share
 * the same TerrainEvaluator code path as the snapshot-driven background calculation,
 * without needing two copies of that logic.
 */
public final class LiveTerrainAccess implements TerrainAccess {

    private final ServerLevel level;

    public LiveTerrainAccess(ServerLevel level) {
        this.level = level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return level.getBlockState(pos);
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return level.isLoaded(pos);
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return level.isOutsideBuildHeight(pos);
    }

    @Override
    public boolean isSolidRender(BlockPos pos) {
        return level.getBlockState(pos).isSolidRender(level, pos);
    }

    @Override
    public float getDestroySpeed(BlockPos pos) {
        return level.getBlockState(pos).getDestroySpeed(level, pos);
    }
}
