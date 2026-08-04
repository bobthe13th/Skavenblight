package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers FlowFieldCalculator.detectMutualCyclePositions and pickCyclePositionToDrop - see those
 * methods' own docs and breakMutualCycles's doc for why this check exists: project-instruction
 * writes bypass the cost-comparison guard that keeps the ordinary core-flood Dijkstra step
 * acyclic, so independently-produced instruction chains can end up pointing back around at each
 * other, forming a cycle of any length within one region's own instruction map.
 * Reproduces the exact 2-cell cycle found in siege_dump_2026-07-30_17-42-19.txt
 * ((1,-61,-20) <-> (1,-60,-20), directly under a floating nexus).
 */
class FlowFieldCalculatorTest {

    private static final BlockPos TARGET = new BlockPos(1, 11, -19);

    @Test
    void emptyMapHasNoCycles() {
        assertTrue(FlowFieldCalculator.detectMutualCyclePositions(Map.of()).isEmpty());
    }

    @Test
    void straightChainToTargetHasNoCycles() {
        BlockPos a = new BlockPos(1, -60, -20);
        BlockPos b = new BlockPos(1, -61, -20);
        BlockPos c = new BlockPos(1, -62, -20);

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        instructions.put(a, new SiegeNode(TARGET, SiegeNode.SiegeAction.WALK));
        instructions.put(b, new SiegeNode(a, SiegeNode.SiegeAction.BUILD_PILLAR));
        instructions.put(c, new SiegeNode(b, SiegeNode.SiegeAction.BUILD_SPIRAL));

        assertTrue(FlowFieldCalculator.detectMutualCyclePositions(instructions).isEmpty());
    }

    @Test
    void selfReferencingLocalObjectiveIsNotACycle() {
        // Every region's own local Dijkstra target self-references (see
        // FlowFieldCalculator.startCalculation) - this is the expected end of a chain, not a bug.
        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        instructions.put(TARGET, new SiegeNode(TARGET, SiegeNode.SiegeAction.WALK));

        assertTrue(FlowFieldCalculator.detectMutualCyclePositions(instructions).isEmpty());
    }

    @Test
    void mutualPairIsDetected() {
        // The exact cycle from the live dump: (1,-61,-20) <-> (1,-60,-20).
        BlockPos upper = new BlockPos(1, -60, -20);
        BlockPos lower = new BlockPos(1, -61, -20);

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        instructions.put(upper, new SiegeNode(lower, SiegeNode.SiegeAction.BUILD_PILLAR));
        instructions.put(lower, new SiegeNode(upper, SiegeNode.SiegeAction.BUILD_SPIRAL));

        Set<BlockPos> cyclePositions = FlowFieldCalculator.detectMutualCyclePositions(instructions);

        assertEquals(Set.of(upper, lower), cyclePositions);
    }

    @Test
    void cycleDetectedAmongUnrelatedLegitimateEntries() {
        BlockPos upper = new BlockPos(1, -60, -20);
        BlockPos lower = new BlockPos(1, -61, -20);
        BlockPos elsewhereA = new BlockPos(-6, -60, -44);
        BlockPos elsewhereB = new BlockPos(-6, -60, -43);

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        instructions.put(upper, new SiegeNode(lower, SiegeNode.SiegeAction.BUILD_PILLAR));
        instructions.put(lower, new SiegeNode(upper, SiegeNode.SiegeAction.BUILD_SPIRAL));
        instructions.put(elsewhereA, new SiegeNode(elsewhereB, SiegeNode.SiegeAction.WALK));
        instructions.put(elsewhereB, new SiegeNode(TARGET, SiegeNode.SiegeAction.WALK));

        Set<BlockPos> cyclePositions = FlowFieldCalculator.detectMutualCyclePositions(instructions);

        assertEquals(Set.of(upper, lower), cyclePositions);
        assertFalse(cyclePositions.contains(elsewhereA));
        assertFalse(cyclePositions.contains(elsewhereB));
    }

    @Test
    void multipleIndependentCyclesAreAllDetected() {
        BlockPos a1 = new BlockPos(1, -60, -20);
        BlockPos a2 = new BlockPos(1, -61, -20);
        BlockPos b1 = new BlockPos(10, 5, 10);
        BlockPos b2 = new BlockPos(10, 6, 10);

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        instructions.put(a1, new SiegeNode(a2, SiegeNode.SiegeAction.BUILD_PILLAR));
        instructions.put(a2, new SiegeNode(a1, SiegeNode.SiegeAction.BUILD_SPIRAL));
        instructions.put(b1, new SiegeNode(b2, SiegeNode.SiegeAction.MINE));
        instructions.put(b2, new SiegeNode(b1, SiegeNode.SiegeAction.BUILD_STAIR));

        Set<BlockPos> cyclePositions = FlowFieldCalculator.detectMutualCyclePositions(instructions);

        assertEquals(Set.of(a1, a2, b1, b2), cyclePositions);
    }

    @Test
    void threeCycleIsDetected() {
        // A -> B -> C -> A: no direct pair points straight back at its own sender, but it's just
        // as much an infinite loop for a mob as a 2-cell cycle - Finding B's whole point.
        BlockPos a = new BlockPos(1, -60, -20);
        BlockPos b = new BlockPos(1, -61, -20);
        BlockPos c = new BlockPos(1, -62, -20);

        Map<BlockPos, SiegeNode> instructions = new HashMap<>();
        instructions.put(a, new SiegeNode(b, SiegeNode.SiegeAction.BUILD_PILLAR));
        instructions.put(b, new SiegeNode(c, SiegeNode.SiegeAction.BUILD_SPIRAL));
        instructions.put(c, new SiegeNode(a, SiegeNode.SiegeAction.BUILD_STAIR));

        Set<BlockPos> cyclePositions = FlowFieldCalculator.detectMutualCyclePositions(instructions);

        assertEquals(Set.of(a, b, c), cyclePositions);
    }

    @Test
    void runUpIntoCycleIsNotItselfPartOfTheCycle() {
        // D -> A -> B -> C -> A: D points INTO the 3-cycle but is never revisited itself, so it's
        // a straight run-up, not a loop - only {A, B, C} should come back, never D.
        BlockPos d = new BlockPos(1, -59, -20);
        BlockPos a = new BlockPos(1, -60, -20);
        BlockPos b = new BlockPos(1, -61, -20);
        BlockPos c = new BlockPos(1, -62, -20);

        // detectMutualCyclePositions walks instructionMap.keySet() in iteration order, resolving
        // (and thus short-circuiting future walks over) every position it touches along the way.
        // A plain HashMap's iteration order for these BlockPos keys happens to visit c (or another
        // cycle member) as a walk's own starting point BEFORE d ever gets its turn - which
        // resolves the whole {a,b,c} cycle with seenAt=0, so d's run-up never produces a nonzero
        // seenAt to trim. That made path.subList(seenAt, ...) indistinguishable from a mutated
        // path.subList(0, ...) for this test, even though the mutation is a real regression (it
        // would fold a genuine run-up into the reported cycle in other orderings). A LinkedHashMap
        // with d inserted first forces the intended walk order: start at d, run up through a/b/c,
        // then close the loop back at a with the cycle already appended after d in `path` - only
        // then does seenAt land at a nonzero index (1, not 0), actually exercising the trim.
        Map<BlockPos, SiegeNode> instructions = new LinkedHashMap<>();
        instructions.put(d, new SiegeNode(a, SiegeNode.SiegeAction.WALK));
        instructions.put(a, new SiegeNode(b, SiegeNode.SiegeAction.BUILD_PILLAR));
        instructions.put(b, new SiegeNode(c, SiegeNode.SiegeAction.BUILD_SPIRAL));
        instructions.put(c, new SiegeNode(a, SiegeNode.SiegeAction.BUILD_STAIR));

        Set<BlockPos> cyclePositions = FlowFieldCalculator.detectMutualCyclePositions(instructions);

        assertEquals(Set.of(a, b, c), cyclePositions);
        assertFalse(cyclePositions.contains(d));
    }

    // --- pickCyclePositionToDrop (Finding A/E): the asymmetric-drop decision extracted out of
    // breakMutualCycles() itself so it's testable without constructing a full FlowFieldCalculator.
    // Still exercised for real by breakMutualCycles() - see FlowFieldCalculator.

    @Test
    void pickCyclePositionToDrop_keepsTheSoleLockedPositionEvenWhenItsThePricierOne() {
        BlockPos upper = new BlockPos(1, -60, -20);
        BlockPos lower = new BlockPos(1, -61, -20);
        List<BlockPos> cycle = List.of(upper, lower);

        // `lower` is both the LOCKED position AND the more expensive one - plain cost logic alone
        // would drop `lower` (higher cost), but locking must override cost: `lower` is an active
        // project's claimed cell and must never be dropped, since the core flood skips locked
        // positions and could never refill it. Confirms locking actually overrides cost rather
        // than merely agreeing with it.
        Map<BlockPos, Integer> costMap = new HashMap<>();
        costMap.put(upper, 1);
        costMap.put(lower, 5);
        Set<BlockPos> lockedPositions = Set.of(lower);

        BlockPos dropped = FlowFieldCalculator.pickCyclePositionToDrop(cycle, costMap, lockedPositions);

        assertEquals(upper, dropped);
    }

    @Test
    void pickCyclePositionToDrop_keepsAllLockedPositionsWhenSomeButNotAllAreLocked() {
        // A 3-cycle where TWO positions belong to different active projects' locked cells and the
        // third is unlocked - realistic once SiegeProjectManager holds multiple concurrent
        // activeProjects contributing to the same shared lockedPositions set, and Finding B's
        // any-length cycle detection means a cycle can legitimately span more than one of them.
        // lockedCount (2) is neither 0 nor == cyclePositions.size() (3), so the fix's
        // "at least one but not all locked" branch must kick in - a naive `lockedCount == 1` check
        // would miss this entirely and let the plain cost walk consider (and possibly drop) a
        // locked position.
        BlockPos lockedA = new BlockPos(1, -60, -20);
        BlockPos unlocked = new BlockPos(1, -61, -20);
        BlockPos lockedB = new BlockPos(1, -62, -20);
        List<BlockPos> cycle = List.of(lockedA, unlocked, lockedB);

        // `unlocked` is deliberately the CHEAPEST position, and `lockedB` the most expensive - a
        // plain cost-only walk over the full cycle (what the old, buggy `lockedCount == 1` check
        // falls through to here, since 2 != 1) would wrongly pick `lockedB` (highest cost) to
        // drop, a locked cell it can never refill. The fix must restrict candidates to `unlocked`
        // regardless of cost, since it's the only unlocked position in this 2-of-3-locked cycle -
        // this is what actually distinguishes the fixed condition from the old one, rather than
        // both happening to agree by coincidence.
        Map<BlockPos, Integer> costMap = new HashMap<>();
        costMap.put(lockedA, 1);
        costMap.put(unlocked, 2);
        costMap.put(lockedB, 100);
        Set<BlockPos> lockedPositions = Set.of(lockedA, lockedB);

        BlockPos dropped = FlowFieldCalculator.pickCyclePositionToDrop(cycle, costMap, lockedPositions);

        assertEquals(unlocked, dropped);
    }

    @Test
    void pickCyclePositionToDrop_dropsHigherCostWhenNeitherLocked() {
        BlockPos upper = new BlockPos(1, -60, -20);
        BlockPos lower = new BlockPos(1, -61, -20);
        List<BlockPos> cycle = List.of(upper, lower);

        Map<BlockPos, Integer> costMap = new HashMap<>();
        costMap.put(upper, 12);
        costMap.put(lower, 3);

        BlockPos dropped = FlowFieldCalculator.pickCyclePositionToDrop(cycle, costMap, Set.of());

        assertEquals(upper, dropped);
    }

    @Test
    void pickCyclePositionToDrop_tiedOrAbsentCostDropsLaterEncountered() {
        BlockPos first = new BlockPos(1, -60, -20);
        BlockPos second = new BlockPos(1, -61, -20);
        BlockPos third = new BlockPos(1, -62, -20);
        List<BlockPos> cycle = List.of(first, second, third);

        // No costMap entries at all (typical of a macro-project chain's interior positions) -
        // with nothing to compare, the position encountered LAST while walking the cycle is
        // dropped, keeping the first one encountered.
        BlockPos dropped = FlowFieldCalculator.pickCyclePositionToDrop(cycle, new HashMap<>(), Set.of());

        assertEquals(third, dropped);
    }

    @Test
    void pickCyclePositionToDrop_fallsBackToCostWhenEveryPositionIsLocked() {
        // If every position in the cycle is locked, there's no unlocked alternative to prefer -
        // the cycle still has to be broken, so this falls through to the plain cost/tiebreak walk
        // over the full set rather than deadlocking with nothing eligible to drop.
        BlockPos upper = new BlockPos(1, -60, -20);
        BlockPos lower = new BlockPos(1, -61, -20);
        List<BlockPos> cycle = List.of(upper, lower);

        Map<BlockPos, Integer> costMap = new HashMap<>();
        costMap.put(upper, 9);
        costMap.put(lower, 2);
        Set<BlockPos> lockedPositions = Set.of(upper, lower);

        BlockPos dropped = FlowFieldCalculator.pickCyclePositionToDrop(cycle, costMap, lockedPositions);

        assertEquals(upper, dropped);
    }
}
