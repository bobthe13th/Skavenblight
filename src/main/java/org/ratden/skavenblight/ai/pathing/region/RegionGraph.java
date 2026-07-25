package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.SiegeLineTracer;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.TerrainEvaluator;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;
import org.slf4j.Logger;

import java.util.*;

/**
 * All regions + the connectors between them for one territory snapshot. Edges are discovered
 * by tracing from every region's boundary cells in the same 14 directions
 * SiegeProjectManager.evaluateMacroProjects already uses, keeping only the cheapest connector
 * found per region pair.
 */
public class RegionGraph {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[][] CARDINAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final RegionIndex regionIndex;
    private final Map<Integer, List<RegionConnector>> connectorsByRegion = new HashMap<>();
    private final List<RegionConnector> allConnectors = new ArrayList<>();

    private RegionGraph(RegionIndex regionIndex) {
        this.regionIndex = regionIndex;
    }

    public static RegionGraph build(TerrainSnapshot snapshot, RegionIndex regionIndex, Set<ChunkPos> bounds,
                                     BlockPos boundsAnchor, TerrainEvaluator evaluator, SiegeLineTracer lineTracer) {
        RegionGraph graph = new RegionGraph(regionIndex);
        FlowFieldState boundsState = new FlowFieldState(boundsAnchor, bounds);

        // regionId pair -> cheapest connector found so far for that pair
        Map<Long, RegionConnector> bestPerPair = new HashMap<>();

        for (Region region : regionIndex.getRegions()) {
            for (BlockPos boundaryCell : region.getBoundaryCells()) {
                for (int dy : new int[]{-1, 1}) {
                    tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, 0, dy, 0, bestPerPair);
                }
                for (int[] dir : CARDINAL_OFFSETS) {
                    for (int dy : new int[]{-1, 0, 1}) {
                        tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, dir[0], dy, dir[1], bestPerPair);
                    }
                }
            }
        }

        for (RegionConnector connector : bestPerPair.values()) {
            graph.allConnectors.add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionA(), k -> new ArrayList<>()).add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionB(), k -> new ArrayList<>()).add(connector);
        }

        LOGGER.info("[Skavenblight] RegionGraph built: {} regions, {} connectors", regionIndex.getRegions().size(), graph.allConnectors.size());
        return graph;
    }

    private static void tryTrace(TerrainSnapshot snapshot, TerrainEvaluator evaluator, SiegeLineTracer lineTracer,
                                  RegionIndex regionIndex, FlowFieldState boundsState, Region fromRegion,
                                  BlockPos anchor, int dx, int dy, int dz, Map<Long, RegionConnector> bestPerPair) {

        SiegeLineTracer.TraceResult result = lineTracer.trace(snapshot, anchor, dx, dy, dz, anchor, 0,
                pos -> evaluator.isOutOfBounds(snapshot, pos, boundsState), pos -> Integer.MAX_VALUE);

        if (!result.completed() || result.instructions().isEmpty()) return;

        Region toRegion = regionIndex.regionAt(result.endPos());
        if (toRegion == null || toRegion.getId() == fromRegion.getId()) return;

        long pairKey = pairKey(fromRegion.getId(), toRegion.getId());
        RegionConnector existing = bestPerPair.get(pairKey);
        if (existing != null && existing.cost() <= result.totalCost()) return;

        SiegeProject project = new SiegeProject(result.instructions(), result.endPos(), result.totalCost());
        RegionConnector connector = new RegionConnector(fromRegion.getId(), toRegion.getId(), anchor, result.endPos(), result.totalCost(), project);
        bestPerPair.put(pairKey, connector);
    }

    private static long pairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return (((long) lo) << 32) | (hi & 0xFFFFFFFFL);
    }

    public List<RegionConnector> getConnectorsFor(int regionId) {
        return connectorsByRegion.getOrDefault(regionId, List.of());
    }

    public List<RegionConnector> getAllConnectors() {
        return List.copyOf(allConnectors);
    }

    public RegionIndex getRegionIndex() {
        return regionIndex;
    }
}
