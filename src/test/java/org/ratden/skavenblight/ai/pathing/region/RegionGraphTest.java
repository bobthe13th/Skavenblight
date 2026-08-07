package org.ratden.skavenblight.ai.pathing.region;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.ratden.skavenblight.ai.pathing.PathStepEvaluator;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers confirmedUnreachableRegionIds' own filtering logic in isolation, via the package-private
 * {@code RegionGraph.forTesting} seam (hand-built regions/connectors, no real tracing). Real
 * connector-DISCOVERY coverage - whether build() actually finds a connector across a given gap,
 * bedrock-tier mining included - needs a real TerrainSnapshot with no meaningful fake, so that
 * lives in the Task 21+ GameTest matrix instead (same reasoning Task 10 uses for skipping a
 * RegionFlowField unit test).
 */
class RegionGraphTest {

    private static Region region(int id) {
        return new Region(id, 0, 256);
    }

    @Test
    void regionWithNoConnectorWithinTheChainHopLimitIsInTheConfirmedUnreachableSet() {
        // No connector at all between region 0 and region 1 - the real build() case this
        // represents is "no traceable connector in any of the 14 fan-out directions from any
        // boundary cell within MAX_CHAIN_HOPS, even considering bedrock-tier mining" - a genuinely
        // rare case now that there's no gating flag left to disable (this task's own correction).
        RegionGraph graph = RegionGraph.forTesting(List.of(region(0), region(1)), List.of());
        RegionRouteTree tree = RegionRouteTree.compute(graph, 0);

        Set<Integer> unreachable = graph.confirmedUnreachableRegionIds(0, tree);

        assertEquals(Set.of(1), unreachable);
    }

    @Test
    void regionWithAnyConnectorIncludingABedrockTierOneIsNeverInTheConfirmedUnreachableSet() {
        // A single connector joining region 0 and region 1, priced at the real bedrock-tier
        // failsafe cost - build() no longer gates bedrock-tier mining behind a flag, so a real
        // bedrock-only gap still produces a genuine (very expensive) RegionConnector. This proves
        // confirmedUnreachableRegionIds doesn't special-case cost at all: ANY connector, cheap or
        // bedrock-tier, makes the region reachable. The connector here is hand-built, not traced -
        // real proof that build() actually offers a bedrock candidate across a sealed gap lives in
        // the GameTest matrix (Task 21+), not here.
        int bedrockTierCost = PathStepEvaluator.bedrockFailsafeWorkUnits();
        RegionConnector connector = new RegionConnector(0, 1, new BlockPos(0, 64, 0), new BlockPos(100, 64, 0),
                bedrockTierCost, null, null);
        RegionGraph graph = RegionGraph.forTesting(List.of(region(0), region(1)), List.of(connector));
        RegionRouteTree tree = RegionRouteTree.compute(graph, 0);

        Set<Integer> unreachable = graph.confirmedUnreachableRegionIds(0, tree);

        assertTrue(unreachable.isEmpty());
    }
}
