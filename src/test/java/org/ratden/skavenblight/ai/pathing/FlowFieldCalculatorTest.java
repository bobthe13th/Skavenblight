package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers FlowFieldCalculator.detectMutualCyclePositions - see that method's own doc and
 * breakMutualCycles's doc for why this check exists: project-instruction writes bypass the
 * cost-comparison guard that keeps the ordinary core-flood Dijkstra step acyclic, so two
 * independently-produced instruction chains can end up pointing straight at each other.
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
}
