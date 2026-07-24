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
        int scannedCells = 0;

        for (ChunkPos chunk : bounds) {
            if (!snapshot.hasColumn(chunk)) continue;

            for (int y = minBuildHeight; y < maxBuildHeight; y++) {
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        BlockPos seed = new BlockPos(chunk.getMinBlockX() + localX, y, chunk.getMinBlockZ() + localZ);

                        if (visited.contains(seed) || !terrainEvaluator.isWalkableTerrain(snapshot, seed)) continue;

                        if (scannedCells >= MAX_SCANNED_CELLS) {
                            LOGGER.warn("[Skavenblight] RegionScanner hit MAX_SCANNED_CELLS ({}) - territory may be under-scanned this pass", MAX_SCANNED_CELLS);
                            return regions;
                        }

                        Region region = new Region(nextId++, minBuildHeight, height);
                        scannedCells += floodFill(snapshot, boundsState, seed, visited, region);
                        regions.add(region);
                    }
                }
            }
        }

        LOGGER.info("[Skavenblight] RegionScanner found {} regions ({} cells scanned)", regions.size(), scannedCells);
        return regions;
    }

    private int floodFill(TerrainSnapshot snapshot, FlowFieldState boundsState, BlockPos seed,
                           Set<BlockPos> visited, Region region) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        int cellsVisited = 0;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            region.addCell(current);
            cellsVisited++;

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

        return cellsVisited;
    }
}
