package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.SiegeLineTracer;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
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

        // Two orientations of the one traced line - see RegionConnector's doc. The entry position
        // differs per orientation because it names the block where mobs ENTER the project: crossing
        // toward the anchor's region they enter at the far end (endPos), crossing away from it they
        // enter at the anchor itself.
        SiegeProject towardA = new SiegeProject(inboundInstructions(anchor, result.orderedSteps()), result.endPos(), result.totalCost());
        SiegeProject towardB = new SiegeProject(outboundInstructions(anchor, result.orderedSteps()), anchor, result.totalCost());
        RegionConnector connector = new RegionConnector(fromRegion.getId(), toRegion.getId(), anchor, result.endPos(),
                result.totalCost(), towardA, towardB);
        bestPerPair.put(pairKey, connector);
    }

    /**
     * Instruction map for crossing a traced line in the OUTWARD direction: from {@code anchor}
     * (a boundary cell of the region that discovered the line) to the far end. Each key is a
     * position a mob can occupy; its node names the next position along the line plus the action
     * needed AT that position, which is the pairing goal code consumes (RegionFlowField
     * #getNextSiegeNode checks completion at {@code node.pos()}, then the construction/breach goals
     * act on {@code node.pos()} with {@code node.action()}).
     *
     * <p>Deliberately NOT reusing SiegeLineTracer's own {@code instructions} map: that one pairs a
     * position's action with the position one step BEFORE it, so its entry at the line's end reads
     * "WALK into the next block" even when that block is solid stone needing a MINE - a mob there
     * asks a breach goal for nothing and stalls (findEffectiveNode's look-ahead then reaches PAST
     * the adjacent block, mining every other block and leaving gaps). Pairing each hop with its own
     * action makes every step of the crossing adjacent and self-describing.
     */
    private static Map<BlockPos, SiegeNode> outboundInstructions(BlockPos anchor, List<SiegeNode> steps) {
        Map<BlockPos, SiegeNode> map = new HashMap<>();
        BlockPos from = anchor;
        for (SiegeNode step : steps) {
            // step already IS (position stepped into, action for that position).
            map.put(from, step);
            from = step.pos();
        }
        return map;
    }

    /**
     * Mirror image of {@link #outboundInstructions}: crossing the same line INWARD, from its far end
     * back to {@code anchor}. The anchor is walkable ground by construction (it's a region boundary
     * cell the trace started from), so the last hop into it is a plain WALK.
     */
    private static Map<BlockPos, SiegeNode> inboundInstructions(BlockPos anchor, List<SiegeNode> steps) {
        Map<BlockPos, SiegeNode> map = new HashMap<>();
        for (int i = steps.size() - 1; i >= 0; i--) {
            BlockPos from = steps.get(i).pos();
            BlockPos to = (i == 0) ? anchor : steps.get(i - 1).pos();
            SiegeNode.SiegeAction action = (i == 0) ? SiegeNode.SiegeAction.WALK : steps.get(i - 1).action();
            map.put(from, new SiegeNode(to, action));
        }
        return map;
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
