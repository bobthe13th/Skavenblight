package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Covers TerrainEvaluator.isActionCompleted's WALK case. A WALK step places/removes nothing, so it
// must be trivially "already done" the moment the mob can genuinely stand there. Without this
// case, a reactive SiegeProject's own trace - which always ends its instructions map with a WALK
// entry at its entryPos (SiegeLineTracer.trace terminates exactly when isWalkableTerrain first
// becomes true) - could never have every one of its instructions.values() entries report
// completed, so SiegeProject.isCompleted() could never return true even after every real
// build/mine step was finished in the world.
class TerrainEvaluatorTest {

    private static class FakeTerrain implements TerrainAccess {
        private final Map<BlockPos, BlockState> states = new HashMap<>();

        void set(BlockPos pos, BlockState state) { states.put(pos, state); }

        @Override
        public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }

        @Override
        public boolean isLoaded(BlockPos pos) { return true; }

        @Override
        public boolean isOutsideBuildHeight(BlockPos pos) { return false; }

        @Override
        public boolean isSolidRender(BlockPos pos) { return getBlockState(pos).blocksMotion(); }

        @Override
        public float getDestroySpeed(BlockPos pos) { return 1.0F; }
    }

    @Test
    void walkStepOntoOpenSupportedGroundReportsCompleted() {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos walkPos = new BlockPos(0, 64, 0);
        terrain.set(walkPos.below(), Blocks.STONE.defaultBlockState()); // solid support; walkPos/head left as open air

        // Ground truth: this position genuinely IS walkable right now - nothing is left to build.
        assertTrue(evaluator.isWalkableTerrain(terrain, walkPos),
                "setup sanity: walkPos must actually be walkable, or this test proves nothing");

        boolean completed = evaluator.isActionCompleted(terrain, new SiegeNode(walkPos, SiegeNode.SiegeAction.WALK));

        assertTrue(completed,
                "a WALK step requires no construction and must report completed once the ground is genuinely walkable");
    }
}
