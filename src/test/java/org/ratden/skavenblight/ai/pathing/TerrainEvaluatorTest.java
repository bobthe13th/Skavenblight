package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Covers TerrainEvaluator.isActionCompleted - specifically the WALK case. A WALK step places/
// removes nothing, so it's trivially "already done" the moment the mob can stand there - unlike
// every other action type, which checks real terrain state. Reproduces the secondary mechanism
// from docs/superpowers/specs/2026-08-04-flow-field-locked-cycle-drops-build-stair-bug.md's
// "Follow-ups found during this investigation" section: a reactive SiegeProject's own trace always
// ends its instructions map with a WALK entry at its entryPos (SiegeLineTracer.trace terminates
// exactly when isWalkableTerrain first becomes true, and that final hop's own determineMacroAction
// call sees open, supported ground and returns WALK) - so SiegeProject.isCompleted(), which
// requires EVERY instructions.values() entry to report completed, could never return true for such
// a project even after every real build/mine step was finished, because the WALK entry itself
// never did. That kept the project in SiegeProjectManager's activeProjects forever
// (injectActiveProjects' removeIf(isCompleted) never fired), so its cells stayed locked and its
// stale instructions kept getting re-injected every pass - confirmed live via repeated identical
// macro-line rediscovery and flow-field cycle-breaks at the same position across an entire test
// session. Now safe to enable: isCompleted()/getRemainingInstructions() were fixed alongside this
// to call isActionCompleted with the real position (the instructions map's KEY), not the raw
// stored value (whose .pos() is the PREDECESSOR position per SiegeLineTracer's anchor-ward
// storage convention).
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
