package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small in-memory TerrainAccess test double, matching the hand-written-fake convention already
 * used throughout this package's tests (see TerrainEvaluatorTest's own FakeTerrain) - this repo has
 * no Mockito dependency, and TerrainAccess is deliberately small enough not to need one. */
class FakeTerrain implements TerrainAccess {

    private final Set<BlockPos> solidPositions = new HashSet<>();
    private final Map<BlockPos, Float> destroySpeeds = new HashMap<>();

    void setSolid(BlockPos pos) {
        solidPositions.add(pos.immutable());
    }

    void setDestroySpeed(BlockPos pos, float speed) {
        destroySpeeds.put(pos.immutable(), speed);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return solidPositions.contains(pos) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return true;
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return false;
    }

    @Override
    public boolean isSolidRender(BlockPos pos) {
        return solidPositions.contains(pos);
    }

    @Override
    public float getDestroySpeed(BlockPos pos) {
        return destroySpeeds.getOrDefault(pos.immutable(), solidPositions.contains(pos) ? 1.5F : -1F);
    }
}
