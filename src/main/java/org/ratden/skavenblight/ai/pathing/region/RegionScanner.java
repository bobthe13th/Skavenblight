package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.Config;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.PathStepEvaluator;
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

    // PathStepEvaluator's HORIZONTAL_OFFSETS holds 8 entries (4 cardinal + 4 diagonal), so an
    // ordinary open flat cell in the middle of walkable space offers 8 WALK steps - one per
    // direction. A cell sitting against a straight wall or cliff edge loses exactly 3 of them
    // (the blocked direction plus its two flanking diagonals), so 6 is the highest threshold that
    // still excludes healthy interior cells while catching a straight boundary line. The old
    // "steps.size() < 4" test was calibrated for a 4-neighborhood: with 8 offsets, a straight
    // boundary line (5 remaining) never tripped it, so RegionGraph - which only traces connectors
    // outward from boundary cells - had nothing to trace from along flat walls/cliffs.
    private static final int BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD = 6;

    private final PathStepEvaluator pathStepEvaluator;

    public RegionScanner(PathStepEvaluator pathStepEvaluator) {
        this.pathStepEvaluator = pathStepEvaluator;
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

                        if (settled.contains(seed) || !pathStepEvaluator.isWalkableTerrain(snapshot, seed)) continue;

                        if (scannedCells[0] >= Config.regionScanMaxCells) {
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
            LOGGER.warn("[Skavenblight] RegionScanner hit regionScanMaxCells ({}) - territory may be under-scanned this pass", Config.regionScanMaxCells);
        }

        LOGGER.info("[Skavenblight] RegionScanner found {} regions ({} cells scanned)", regions.size(), scannedCells[0]);
        return regions;
    }

    /**
     * Dijkstra flood-fill, not a plain BFS: relaxes cost whenever a cheaper path to a cell is
     * found, rather than a plain unweighted BFS's {@code visited.add(pos)}-once gate - matching
     * FlowFieldCalculator's own real Dijkstra so both traversals settle cells via the same rule.
     *
     * <p>{@code floodFill} only ever enqueues {@code PathAction.WALK} candidates from
     * {@code candidateSteps} - never TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR. Region membership must
     * reflect ONLY genuine walkable connectivity, not "reachable via a construction candidate up
     * to some depth cap" the way the pre-Task-8 MINE-step version allowed: a TUNNEL candidate is a
     * construction edge (RegionGraph's job to discover), and if this flood ever followed one,
     * every obstacle a SiegeProject could bridge would silently fuse the regions on either side of
     * it, destroying the partition this class exists to produce. With the flood WALK-only, there
     * is no construction chain left to bound - no depth cap is needed. Confirmed via
     * {@code PathingRegionGameTests.testRepeatedConnectorCompletionsDontExplodeRebuildCount}: a
     * solid, non-walkable cell that the OLD MINE-step version swept into a region as a side effect
     * (167 cells) is now correctly excluded (166 cells) - this WALK-only behavior is a deliberate
     * fix, not a regression, but it does mean any OTHER code that assumed a region could contain a
     * non-walkable cell needs re-checking (see this task's own commit message).
     *
     * <p><b>Temporarily divergent from FlowFieldCalculator (until Task 6/exec-7):</b> this class
     * now uses {@code PathStepEvaluator} exclusively, while {@code FlowFieldCalculator} still runs
     * the old {@code TerrainEvaluator} with MINE steps and {@code MAX_CONSECUTIVE_MINE_DEPTH} until
     * its own port lands. Until then, a region's WALK-only membership and the flow field's own
     * MINE-inclusive traversal are NOT guaranteed to agree at every cell - the historical bug this
     * javadoc used to warn about (a flow-field path pointing into a cell outside any region,
     * indistinguishable in-game from a literal loop) is a real risk in this window. Task 6 restores
     * the shared-generator equivalence by porting FlowFieldCalculator onto the same
     * {@code PathStepEvaluator}/WALK-only-membership model.
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

        costMap.put(seed, 0);
        queue.add(new QueueNode(seed, 0));

        while (!queue.isEmpty()) {
            // Checked every iteration (not just between seeds in scan()) so a single large
            // connected region - the common case, e.g. "a single unbroken room" - can't flood
            // through an entire territory's full-build-height column in one uninterrupted pass
            // before the cap is ever consulted again.
            if (scannedCells[0] >= Config.regionScanMaxCells) {
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

            List<PathStepEvaluator.EvaluatedStep> steps = pathStepEvaluator.candidateSteps(
                    snapshot, current, Collections.emptySet(), pos -> pathStepEvaluator.isOutOfBounds(snapshot, pos, boundsState));

            // Only WALK steps count toward "am I at the edge of walkable space". The raw step
            // count can't answer that at all: a cell facing a solid wall still gets a full 8
            // steps, because every blocked direction is offered back as a construction candidate.
            // Likewise only cells that are themselves standable are recorded - RegionGraph uses a
            // boundary cell directly as a connector's entry point (the spot a mob has to reach),
            // so a cell that only exists in this region because a construction chain passed
            // through solid rock is not a valid anchor to trace one from.
            int walkableNeighbors = 0;
            for (PathStepEvaluator.EvaluatedStep step : steps) {
                if (step.action() == PathAction.WALK) walkableNeighbors++;
            }
            if (walkableNeighbors < BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD
                    && pathStepEvaluator.isWalkableTerrain(snapshot, current)) {
                region.addBoundaryCell(current);
            }

            for (PathStepEvaluator.EvaluatedStep step : steps) {
                if (step.action() != PathAction.WALK) continue; // construction edges are RegionGraph's job, not this flood's
                if (settled.contains(step.pos())) continue; // already claimed by another region

                int totalCost = currentCost + step.cost();

                if (totalCost < costMap.getOrDefault(step.pos(), Integer.MAX_VALUE)) {
                    costMap.put(step.pos(), totalCost);
                    queue.add(new QueueNode(step.pos(), totalCost));
                }
            }
        }
    }
}
