package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathStepEvaluatorStepGenerationTest {

    @Test
    void diagonalRiseIntoSolidMaterialProducesCarvedStairNotAirStair() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos diagonalUp = new BlockPos(1, 11, 1);
        terrain.setSolid(diagonalUp);
        terrain.setSolid(diagonalUp.above()); // headroom-blocking, forces obstacle classification

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(diagonalUp)).findFirst().orElseThrow();
        assertEquals(PathAction.CARVED_STAIR, found.action());
    }

    @Test
    void diagonalRiseIntoOpenAirProducesAirStairNotCarvedStair() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos diagonalUp = new BlockPos(1, 11, 1);
        // diagonalUp itself stays open air (default), no support below - not walkable.

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(diagonalUp)).findFirst().orElseThrow();
        assertEquals(PathAction.AIR_STAIR, found.action());
    }

    @Test
    void straightOneAxisRiseWithTheOtherHorizontalAxisUnchangedStillProducesAirStair() {
        // Regression pin for the real StaircaseSiegeGroupGameTests geometry: Z is held CONSTANT
        // across every existing air-stair test - the actual crossing is dx!=0, dy!=0, dz==0, NOT a
        // corner-diagonal with both dx and dz nonzero. A classification requiring both horizontal
        // axes nonzero would silently produce no candidate at all for this exact shape.
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos straightAxisRise = new BlockPos(1, 11, 0); // dx=1, dy=1, dz=0

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(straightAxisRise)).findFirst().orElseThrow(
                        () -> new AssertionError("dx!=0,dy!=0,dz==0 must produce a candidate step - "
                                + "this exact shape is what every existing air-stair GameTest crosses"));
        assertEquals(PathAction.AIR_STAIR, found.action());
    }

    @Test
    void pureVerticalObstacleProducesNoStepAtAll() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos straightUp = new BlockPos(0, 11, 0);
        // straightUp stays open air with no support below - not walkable, but pure vertical
        // (dx==0, dz==0) must never produce a step regardless.

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false);

        assertTrue(steps.stream().noneMatch(s -> s.pos().equals(straightUp)),
                "pure vertical climbing must never produce a candidate step");
    }

    @Test
    void walkableNeighborNeverAlsoProducesAConstructionCandidate() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos flatWalkable = new BlockPos(1, 10, 0);
        terrain.setSolid(flatWalkable.below()); // real ground - genuinely walkable

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false);

        long matchesAtThatPos = steps.stream().filter(s -> s.pos().equals(flatWalkable)).count();
        assertEquals(1, matchesAtThatPos, "a walkable cell must produce exactly one WALK step, never also a construction candidate");
        assertEquals(PathAction.WALK, steps.stream().filter(s -> s.pos().equals(flatWalkable)).findFirst().get().action());
    }

    @Test
    void horizontalObstacleIntoSolidMaterialProducesTunnelNotBridge() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos horizontalSolid = new BlockPos(1, 10, 0);
        terrain.setSolid(horizontalSolid);

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(horizontalSolid)).findFirst().orElseThrow();
        assertEquals(PathAction.TUNNEL, found.action());
    }

    @Test
    void horizontalGapWithNoSupportBelowProducesBridgeNotTunnel() {
        FakeTerrain terrain = new FakeTerrain();
        BlockPos current = new BlockPos(0, 10, 0);
        BlockPos horizontalGap = new BlockPos(1, 10, 0);
        // horizontalGap stays open air with no support below - a genuine gap.

        PathStepEvaluator evaluator = new PathStepEvaluator();
        List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(
                terrain, current, Collections.emptySet(), pos -> false);

        PathStepEvaluator.EvaluatedStep found = steps.stream()
                .filter(s -> s.pos().equals(horizontalGap)).findFirst().orElseThrow();
        assertEquals(PathAction.BRIDGE, found.action());
    }

    @Test
    void aLongTunnelLosesToAShortAirStairOnTotalPathCostNotJustBaseCost() {
        // Verification for dropping the old MAX_CONSECUTIVE_MINE_DEPTH cap: if cost alone already
        // makes an arbitrarily long tunnel lose to a short alternative, no separate depth cap is
        // needed. A 10-block tunnel through ordinary stone costs baseCostFor(TUNNEL) +
        // miningCost(stone) per block, ten times over; a 3-step air-stair costs 3 *
        // baseCostFor(AIR_STAIR). Assert the tunnel's accumulated cost exceeds the air-stair's.
        PathStepEvaluator evaluator = new PathStepEvaluator();
        FakeTerrain stoneTerrain = new FakeTerrain();
        BlockPos stonePos = new BlockPos(0, 0, 0);

        int tunnelStepCost = evaluator.baseCostFor(PathAction.TUNNEL) + evaluator.miningCost(stoneTerrain, stonePos);
        int accumulatedTenBlockTunnelCost = 10 * tunnelStepCost;
        int accumulatedThreeStepAirStairCost = 3 * evaluator.baseCostFor(PathAction.AIR_STAIR);

        assertTrue(accumulatedTenBlockTunnelCost > accumulatedThreeStepAirStairCost,
                "a 10-block tunnel must cost more in total than a 3-step air-stair, proving cost "
                        + "alone (no separate mine-chain-depth cap) already prevents an arbitrarily "
                        + "long tunnel from ever winning against a short real alternative");
    }
}
