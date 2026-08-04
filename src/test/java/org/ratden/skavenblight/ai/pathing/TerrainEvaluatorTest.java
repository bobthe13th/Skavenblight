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

    // isWalkableScaffold's blanket `state.is(Blocks.COBBLESTONE)` exemption treats plain
    // cobblestone - the exact block BUILD_PILLAR/BUILD_BRIDGE/BUILD_LANDING place - as passable in
    // every role it's checked in, including as a ceiling. Unlike StairBlock/SlabBlock/LadderBlock,
    // a full cobblestone block has no partial collision shape - there is no configuration where a
    // solid cobblestone block directly overhead leaves real headroom. Reported by the user as
    // "the flow field ... thinks they can just jump through blocks": a completed pillar/bridge/
    // landing segment crossing overhead was being reported as walkable underneath.
    @Test
    void plainCobblestoneDirectlyOverheadIsNotWalkable() {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos gapPos = new BlockPos(0, 64, 0);
        terrain.set(gapPos.below(), Blocks.STONE.defaultBlockState());
        terrain.set(gapPos.above(), Blocks.COBBLESTONE.defaultBlockState());

        assertFalse(evaluator.isWalkableTerrain(terrain, gapPos),
                "a solid cobblestone block directly overhead is a real ceiling - the cell beneath it " +
                        "must not be reported walkable just because cobblestone happens to be exempted " +
                        "as \"scaffold\" elsewhere");
    }

    // Companion to the cobblestone case above, for StairBlock specifically. Originally written
    // expecting this to require distinguishing "a stair belonging to my own climbing structure"
    // from "an unrelated stair crossing overhead" - a real GameTest (SpiralStaircaseClimbGameTests)
    // settled that no such distinction is needed: a real ClanratEntity using vanilla navigation
    // could not climb past a couple of levels of a 1-wide stacked-stair column even while every
    // level of it read as walkable under the old (exempting) predicate, proving the exemption was
    // never actually load-bearing for real mob movement in the first place.
    @Test
    void stairBlockDirectlyOverheadIsNotWalkable() {
        TerrainEvaluator evaluator = new TerrainEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos gapPos = new BlockPos(10, 64, 10);
        terrain.set(gapPos.below(), Blocks.COBBLESTONE_STAIRS.defaultBlockState());
        terrain.set(gapPos.above(), Blocks.COBBLESTONE_STAIRS.defaultBlockState());

        assertFalse(evaluator.isWalkableTerrain(terrain, gapPos),
                "a stair block directly overhead is a real ceiling for headroom purposes - the gap " +
                        "underneath it must not be reported walkable just because stairs are exempted " +
                        "as standable \"scaffold\" underfoot");
    }
}
