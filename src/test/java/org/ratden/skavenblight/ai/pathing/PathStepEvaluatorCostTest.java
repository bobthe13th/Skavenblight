package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.Config;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathStepEvaluatorCostTest {

    /** Same hand-written double TerrainEvaluatorTest already uses - see that file's own
     * FakeTerrain for the established convention this mirrors exactly. */
    private static class FakeTerrain implements TerrainAccess {
        private final Map<BlockPos, Float> destroySpeeds = new HashMap<>();

        void setDestroySpeed(BlockPos pos, float speed) { destroySpeeds.put(pos, speed); }

        @Override
        public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }

        @Override
        public boolean isLoaded(BlockPos pos) { return true; }

        @Override
        public boolean isOutsideBuildHeight(BlockPos pos) { return false; }

        @Override
        public boolean isSolidRender(BlockPos pos) { return false; }

        @Override
        public float getDestroySpeed(BlockPos pos) { return destroySpeeds.getOrDefault(pos, 1.0F); }
    }

    @Test
    void baseCostsAreStrictlyOrderedTunnelBridgeCarvedStairAirStair() {
        PathStepEvaluator evaluator = new PathStepEvaluator();

        int tunnel = evaluator.baseCostFor(PathAction.TUNNEL);
        int bridge = evaluator.baseCostFor(PathAction.BRIDGE);
        int carvedStair = evaluator.baseCostFor(PathAction.CARVED_STAIR);
        int airStair = evaluator.baseCostFor(PathAction.AIR_STAIR);

        assertTrue(tunnel < bridge, "tunnel (" + tunnel + ") must be cheaper than bridge (" + bridge + ")");
        assertTrue(bridge < carvedStair, "bridge (" + bridge + ") must be cheaper than carved-stair (" + carvedStair + ")");
        assertTrue(carvedStair < airStair, "carved-stair (" + carvedStair + ") must be cheaper than air-stair (" + airStair + ")");
    }

    @Test
    void bedrockLikeBlockCostsExactlyOneRatMinuteOfWorkForOneRat() {
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos pos = new BlockPos(0, 0, 0);
        terrain.setDestroySpeed(pos, -1.0F);

        int expectedWorkUnits = (int) (Config.bedrockFailsafeRatMinutes * 1200 * Config.workPerRatPerTick);
        assertEquals(expectedWorkUnits, evaluator.miningCost(terrain, pos));
    }

    @Test
    void ordinaryBlockUsesHardnessDrivenCostNotBedrockFailsafe() {
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain terrain = new FakeTerrain();
        BlockPos pos = new BlockPos(0, 0, 0);
        terrain.setDestroySpeed(pos, 2.0F);

        int expected = (int) (2.0F * Config.miningPenaltyMultiplier) + Config.miningBasePenalty;
        assertEquals(expected, evaluator.miningCost(terrain, pos));
    }
}
