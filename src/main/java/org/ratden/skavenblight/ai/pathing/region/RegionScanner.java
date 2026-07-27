package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Flood-fills a captured TerrainSnapshot into discrete connectivity-component Regions.
 * Pure function: no mutable state, no threading concerns of its own (safe to run on the
 * same background thread FlowFieldCalculator already uses for terrain-snapshot-based work).
 */
public final class RegionScanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Overall cell budget for one scan pass, mirroring Config.maxFlowFieldNodes' role for the
    // ordinary Dijkstra pass - without a cap, a very tall/wide territory's full build-height
    // scan has no upper bound on how long one pass can run.
    private static final int MAX_SCANNED_CELLS = 200_000;

    // Same cap, and for the same reason, as FlowFieldCalculator's private
    // MAX_CONSECUTIVE_MINE_DEPTH (kept as a local constant rather than exposing that one):
    // getValidOrthogonalSteps happily offers a MINE step into any solid block at the current Y,
    // so a flood fill that follows those steps without a depth limit tunnels arbitrarily far
    // through undisturbed rock - which here doesn't just waste budget, it MERGES two regions that
    // are genuinely separated by solid stone, destroying the very partition this class exists to
    // produce (and with it the connector/route-tree work that depends on the partition).
    private static final int MAX_CONSECUTIVE_MINE_DEPTH = 5;

    // TerrainEvaluator.HORIZONTAL_OFFSETS holds 8 entries (4 cardinal + 4 diagonal), so an
    // ordinary open flat cell in the middle of walkable space offers 8 WALK steps - one per
    // direction. A cell sitting against a straight wall or cliff edge loses exactly 3 of them
    // (the blocked direction plus its two flanking diagonals), so 6 is the highest threshold that
    // still excludes healthy interior cells while catching a straight boundary line. The old
    // "steps.size() < 4" test was calibrated for a 4-neighborhood: with 8 offsets, a straight
    // boundary line (5 remaining) never tripped it, so RegionGraph - which only traces connectors
    // outward from boundary cells - had nothing to trace from along flat walls/cliffs.
    private static final int BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD = 6;

    private final TerrainEvaluator terrainEvaluator;

    public RegionScanner(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
    }

    public List<Region> scan(TerrainSnapshot snapshot, Set<ChunkPos> bounds, BlockPos boundsAnchor,
                              int minBuildHeight, int maxBuildHeight) {
        FlowFieldState boundsState = new FlowFieldState(boundsAnchor, bounds);
        int height = maxBuildHeight - minBuildHeight;

        // Settled (finalized) cells across the WHOLE scan, shared by every region's flood, so a
        // cell claimed by one region can never be re-seeded or re-claimed by another.
        Set<BlockPos> settled = new HashSet<>();
        List<Region> regions = new ArrayList<>();
        int nextId = 0;
        // Mutable, shared across the whole scan() call (including every flood it drives) so the
        // budget check inside the flood's own loop sees the true running total, not just the
        // count for the region currently being flooded.
        int[] scannedCells = {0};
        boolean[] budgetExhausted = {false};

        outer:
        for (ChunkPos chunk : bounds) {
            if (!snapshot.hasColumn(chunk)) continue;

            for (int y = minBuildHeight; y < maxBuildHeight; y++) {
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        BlockPos seed = new BlockPos(chunk.getMinBlockX() + localX, y, chunk.getMinBlockZ() + localZ);

                        if (settled.contains(seed) || !terrainEvaluator.isWalkableTerrain(snapshot, seed)) continue;

                        if (scannedCells[0] >= MAX_SCANNED_CELLS) {
                            budgetExhausted[0] = true;
                            break outer;
                        }

                        Region region = new Region(nextId++, minBuildHeight, height);
                        floodFill(snapshot, boundsState, seed, settled, region, scannedCells, budgetExhausted);
                        regions.add(region);

                        // A region's flood can itself exhaust the budget mid-flood (the common
                        // case for one large connected region) - stop seeding any further regions
                        // the moment that happens, same as the between-seeds check above.
                        if (budgetExhausted[0]) {
                            break outer;
                        }
                    }
                }
            }
        }

        if (budgetExhausted[0]) {
            LOGGER.warn("[Skavenblight] RegionScanner hit MAX_SCANNED_CELLS ({}) - territory may be under-scanned this pass", MAX_SCANNED_CELLS);
        }

        LOGGER.info("[Skavenblight] RegionScanner found {} regions ({} cells scanned)", regions.size(), scannedCells[0]);
        return regions;
    }

    /**
     * Dijkstra flood-fill, not a plain BFS: connectivity within one region must agree EXACTLY
     * with FlowFieldCalculator.processOrthogonalNeighbors - the same step generator
     * (TerrainEvaluator.getValidOrthogonalSteps), the same MAX_CONSECUTIVE_MINE_DEPTH cap, and
     * critically the same cost-relaxation rule for both cost AND mine-chain-depth - or the two
     * can settle a shared cell's mine-chain-depth differently and disagree on whether a
     * downstream cell is within the cap. Confirmed in testing: a clean, monotonic flow-field
     * path terminated by pointing into a cell that belonged to no region at all, nowhere near a
     * territory boundary, causing affected mobs to lose their field assignment mid-route, fall
     * back to wilderness wandering, wander back into the mapped area, and repeat the same walk
     * out to the same dead end - visually indistinguishable in-game from a literal loop.
     *
     * A plain unweighted BFS (the previous implementation) locks in whichever mine-chain-depth
     * its first, queue-order-dependent visit to a cell happens to find, via
     * {@code visited.add(pos)} as the sole gate, and never revisits it even when a shorter chain
     * is later discovered through a different neighbor. FlowFieldCalculator's real Dijkstra, by
     * contrast, continuously relaxes both cost and mine-chain-depth whenever a cheaper path is
     * found. In a braided area mixing WALK and MINE steps, an unweighted hop-order search and a
     * cost-weighted search settle nodes in genuinely different sequences and can lock in
     * different (and sometimes wrongly-too-deep) mine-chain-depths for the same intermediate
     * cells - which can push a cell just past the cap in one algorithm while the other, having
     * found the true minimum-depth route, brings it in under the cap. Making this a real Dijkstra
     * with the identical relaxation rule removes the divergence at its source: region membership
     * now always reflects the true minimum mine-chain-depth to reach a cell, exactly like the
     * flow field's own instructions do.
     */
    private void floodFill(TerrainSnapshot snapshot, FlowFieldState boundsState, BlockPos seed,
                            Set<BlockPos> settled, Region region, int[] scannedCells, boolean[] budgetExhausted) {
        record QueueNode(BlockPos pos, int cost) implements Comparable<QueueNode> {
            @Override
            public int compareTo(QueueNode other) {
                return Integer.compare(this.cost, other.cost);
            }
        }

        PriorityQueue<QueueNode> queue = new PriorityQueue<>();
        // Best known cost to reach each cell (this flood only - fresh per region, exactly like
        // FlowFieldCalculator.startCalculation clears nextCostMap per calculateFully call).
        Map<BlockPos, Integer> costMap = new HashMap<>();
        // Consecutive-MINE-steps-taken-to-reach-this-position along the CURRENT cheapest known
        // path, exactly as FlowFieldCalculator.processOrthogonalNeighbors tracks it - relaxed
        // (updated) in lockstep with costMap whenever a cheaper path is found, not locked on
        // first visit.
        Map<BlockPos, Integer> mineChainDepth = new HashMap<>();

        costMap.put(seed, 0);
        queue.add(new QueueNode(seed, 0));

        while (!queue.isEmpty()) {
            // Checked every iteration (not just between seeds in scan()) so a single large
            // connected region - the common case, e.g. "a single unbroken room" - can't flood
            // through an entire territory's full-build-height column in one uninterrupted pass
            // before the cap is ever consulted again.
            if (scannedCells[0] >= MAX_SCANNED_CELLS) {
                budgetExhausted[0] = true;
                return;
            }

            QueueNode qNode = queue.poll();
            BlockPos current = qNode.pos();
            int currentCost = qNode.cost();

            // Stale entry - a cheaper path to `current` was already settled via an earlier pop.
            // Standard lazy-deletion Dijkstra: cheaper than removing from the queue's middle.
            if (currentCost > costMap.getOrDefault(current, Integer.MAX_VALUE)) continue;
            if (!settled.add(current)) continue; // already claimed - by this same flood or an earlier region's

            region.addCell(current);
            scannedCells[0]++;

            List<TerrainEvaluator.EvaluatedStep> steps =
                    terrainEvaluator.getValidOrthogonalSteps(snapshot, current, Collections.emptySet(), boundsState);

            // Only WALK steps count toward "am I at the edge of walkable space". The raw step
            // count can't answer that at all: a cell facing a solid wall still gets a full 8
            // steps, because every blocked direction is offered back as a MINE step. Likewise
            // only cells that are themselves standable are recorded - RegionGraph uses a boundary
            // cell directly as a connector's entry point (the spot a mob has to reach), so a cell
            // that only exists in this region because a MINE chain passed through solid rock is
            // not a valid anchor to trace one from.
            int walkableNeighbors = 0;
            for (TerrainEvaluator.EvaluatedStep step : steps) {
                if (step.action() == SiegeNode.SiegeAction.WALK) walkableNeighbors++;
            }
            if (walkableNeighbors < BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD
                    && terrainEvaluator.isWalkableTerrain(snapshot, current)) {
                region.addBoundaryCell(current);
            }

            int currentMineDepth = mineChainDepth.getOrDefault(current, 0);
            for (TerrainEvaluator.EvaluatedStep step : steps) {
                if (settled.contains(step.pos())) continue; // already claimed by another region

                // Reset to 0 on WALK/BUILD_* (the step lands on solid ground); only MINE chains
                // deeper. Past the cap the branch is dropped entirely - it isn't enqueued, so it
                // never becomes a member of this region and can't fuse it to whatever lies on the
                // far side of the rock.
                int stepMineDepth = step.action() == SiegeNode.SiegeAction.MINE ? currentMineDepth + 1 : 0;
                if (stepMineDepth > MAX_CONSECUTIVE_MINE_DEPTH) continue;

                int totalCost = currentCost + step.cost();

                if (totalCost < costMap.getOrDefault(step.pos(), Integer.MAX_VALUE)) {
                    costMap.put(step.pos(), totalCost);
                    mineChainDepth.put(step.pos(), stepMineDepth);
                    queue.add(new QueueNode(step.pos(), totalCost));
                }
            }
        }
    }
}
