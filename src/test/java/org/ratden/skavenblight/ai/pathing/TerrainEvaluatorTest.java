package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Covers TerrainEvaluator.isActionCompleted - specifically the WALK case, which the switch never
// handles (falls to the default false branch). A WALK step places/removes nothing, so it's
// trivially "already done" the moment the mob can stand there - unlike every other action type,
// which checks real terrain state. Reproduces the secondary mechanism from
// docs/superpowers/specs/2026-08-04-flow-field-locked-cycle-drops-build-stair-bug.md's "Follow-ups
// found during this investigation" section: a reactive SiegeProject's own trace always ends its
// instructions map with a WALK entry at its entryPos (SiegeLineTracer.trace terminates exactly
// when isWalkableTerrain first becomes true, and that final hop's own determineMacroAction call
// sees open, supported ground and returns WALK) - so SiegeProject.isCompleted(), which requires
// EVERY instructions.values() entry to report completed, can never return true for such a project
// even after every real build/mine step is finished, because the WALK entry itself never does.
// That keeps the project in SiegeProjectManager's activeProjects forever (injectActiveProjects'
// removeIf(isCompleted) never fires), so its cells stay locked and its stale instructions keep
// getting re-injected every pass.
//
// Deliberately left failing (@Disabled, not fixed) - see the same spec section: isCompleted()/
// getRemainingInstructions() both call isActionCompleted with the raw stored SiegeNode value,
// whose .pos() is the PREDECESSOR position (SiegeLineTracer's anchor-ward storage convention), not
// the real position the map key names. Making WALK terrain-sensitive without first correcting
// that call site would evaluate walkability at the wrong cell everywhere isCompleted() is used -
// a second, independent, larger-blast-radius bug that needs its own fix and its own test first.
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
    @Disabled("Follow-up, not this fix - see class doc: fixing this requires first correcting "
            + "isCompleted()/getRemainingInstructions()'s predecessor-position bug in SiegeProject.java")
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
