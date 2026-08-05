package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiegeLineTracerTest {

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
    void traceFindsNothingToBuildWhenAlreadyWalkableGroundExistsOneLevelBeneathTheOnlyTread() {
        // anchor is a platform edge; tracing diagonally down-and-out (dx=1, dy=-1, dz=0) would
        // normally need one BUILD_STAIR tread before reaching genuinely open ground - except
        // real, already-existing ground (STONE two levels down) sits directly beneath that very
        // tread, one level below where the trace's own fixed diagonal stride expects to find
        // open air. The tread is unnecessary: a mob can already walk onto that real ground
        // directly, one level lower, with no construction at all. Reported live as "the first
        // stair was built on top of a [fill] block, when it should have been placed on the
        // already-existing ground."
        BlockPos anchor = new BlockPos(0, 65, 0);
        BlockPos treadPos = anchor.offset(1, -1, 0);        // (1, 64, 0) - the would-be BUILD_STAIR
        BlockPos alreadyGroundPos = treadPos.below();        // (1, 63, 0) - real ground, one below the tread
        BlockPos endPos = treadPos.offset(1, -1, 0);         // (2, 63, 0) - where the trace naturally terminates

        FakeTerrain terrain = new FakeTerrain();
        terrain.set(alreadyGroundPos.below(), Blocks.STONE.defaultBlockState());
        terrain.set(endPos.below(), Blocks.STONE.defaultBlockState());

        SiegeLineTracer tracer = new SiegeLineTracer(new TerrainEvaluator());

        SiegeLineTracer.TraceResult result = tracer.trace(terrain, anchor, 1, -1, 0,
                null, 0, pos -> false, pos -> Integer.MAX_VALUE);

        assertFalse(result.completed(),
                "the whole point of this line was to cross a gap - once alreadyGroundPos proves the "
                        + "gap doesn't need crossing at all, the trace should find nothing to build here, "
                        + "not commit to an unnecessary BUILD_STAIR at " + treadPos);
    }

    @Test
    void traceStillCompletesAOneSegmentVerticalPillarWithNothingElseAboveTheAnchor() {
        // A one-segment vertical trace (dx=0, dz=0) has tread.pos() == anchorPos.above(), so
        // tread.pos().below() == anchorPos itself - the cell the mob is already standing on,
        // walkable by construction (that's why the flood is here in the first place). This must
        // NOT be mistaken for the BUILD_STAIR overshoot above: there is no fixed-diagonal-stride
        // mismatch in a vertical line, so a genuinely necessary single-block BUILD_PILLAR must
        // still get built.
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos pillarPos = anchor.above();     // (0, 65, 0) - the BUILD_PILLAR target
        BlockPos endPos = pillarPos.above();      // (0, 66, 0) - where the trace naturally terminates

        FakeTerrain terrain = new FakeTerrain();
        terrain.set(anchor, Blocks.STONE.defaultBlockState());      // anchor itself is real, solid ground
        terrain.set(endPos.below(), Blocks.STONE.defaultBlockState()); // support for endPos's own foot... wait see below
        terrain.set(anchor.below(), Blocks.STONE.defaultBlockState());

        SiegeLineTracer tracer = new SiegeLineTracer(new TerrainEvaluator());

        SiegeLineTracer.TraceResult result = tracer.trace(terrain, anchor, 0, 1, 0,
                null, 0, pos -> false, pos -> Integer.MAX_VALUE);

        assertTrue(result.completed(),
                "a genuinely necessary one-segment BUILD_PILLAR must still be built - the anchor "
                        + "itself being walkable ground is not an overshoot, it's simply where the mob "
                        + "is already standing");
    }
}
