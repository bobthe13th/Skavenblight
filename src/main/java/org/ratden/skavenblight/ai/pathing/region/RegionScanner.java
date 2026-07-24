package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
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

    private final TerrainEvaluator terrainEvaluator;

    public RegionScanner(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
    }

    public List<Region> scan(TerrainSnapshot snapshot, Set<ChunkPos> bounds, BlockPos boundsAnchor,
                              int minBuildHeight, int maxBuildHeight) {
        FlowFieldState boundsState = new FlowFieldState(boundsAnchor, bounds);
        int height = maxBuildHeight - minBuildHeight;

        Set<BlockPos> visited = new HashSet<>();
        List<Region> regions = new ArrayList<>();
        int nextId = 0;
        // Mutable, shared across the whole scan() call (including every floodFill it drives) so
        // the budget check inside floodFill's own loop sees the true running total, not just the
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

                        if (visited.contains(seed) || !terrainEvaluator.isWalkableTerrain(snapshot, seed)) continue;

                        if (scannedCells[0] >= MAX_SCANNED_CELLS) {
                            budgetExhausted[0] = true;
                            break outer;
                        }

                        Region region = new Region(nextId++, minBuildHeight, height);
                        floodFill(snapshot, boundsState, seed, visited, region, scannedCells, budgetExhausted);
                        regions.add(region);

                        // floodFill can itself exhaust the budget mid-flood (the common case for
                        // one large connected region) - stop seeding any further regions the
                        // moment that happens, same as the between-seeds check above.
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

    private void floodFill(TerrainSnapshot snapshot, FlowFieldState boundsState, BlockPos seed,
                            Set<BlockPos> visited, Region region, int[] scannedCells, boolean[] budgetExhausted) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        while (!queue.isEmpty()) {
            // Checked every iteration (not just between seeds in scan()) so a single large
            // connected region - the common case, e.g. "a single unbroken room" - can't flood
            // through an entire territory's full-build-height column in one uninterrupted pass
            // before the cap is ever consulted again.
            if (scannedCells[0] >= MAX_SCANNED_CELLS) {
                budgetExhausted[0] = true;
                return;
            }

            BlockPos current = queue.poll();
            region.addCell(current);
            scannedCells[0]++;

            List<TerrainEvaluator.EvaluatedStep> steps =
                    terrainEvaluator.getValidOrthogonalSteps(snapshot, current, Collections.emptySet(), boundsState);

            if (steps.size() < 4) {
                region.addBoundaryCell(current);
            }

            for (TerrainEvaluator.EvaluatedStep step : steps) {
                if (visited.add(step.pos())) {
                    queue.add(step.pos());
                }
            }
        }
    }
}
