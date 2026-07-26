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
    private static final int MAX_CHAIN_HOPS = 12; // 12 * MAX_PROJECT_LENGTH(32) = 384 blocks, comfortably more than Minecraft's full build-height range
    private static final int VERTICAL_CHAIN_SLACK = 32; // one hop's worth of margin past the known region Y-range, so a landing exactly at a region's edge isn't cut off early

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
        // regionId pair -> hop count the winning connector in bestPerPair took to discover
        Map<Long, Integer> hopsPerPair = new HashMap<>();

        int minKnownY = regionIndex.getRegions().stream().mapToInt(r -> r.getMin().getY()).min().orElse(Integer.MIN_VALUE);
        int maxKnownY = regionIndex.getRegions().stream().mapToInt(r -> r.getMax().getY()).max().orElse(Integer.MAX_VALUE);

        for (Region region : regionIndex.getRegions()) {
            for (BlockPos boundaryCell : region.getBoundaryCells()) {
                for (int dy : new int[]{-1, 1}) {
                    tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, 0, dy, 0, minKnownY, maxKnownY, bestPerPair, hopsPerPair);
                }
                for (int[] dir : CARDINAL_OFFSETS) {
                    for (int dy : new int[]{-1, 0, 1}) {
                        tryTrace(snapshot, evaluator, lineTracer, regionIndex, boundsState, region, boundaryCell, dir[0], dy, dir[1], minKnownY, maxKnownY, bestPerPair, hopsPerPair);
                    }
                }
            }
        }

        int chainedCount = 0;
        int maxHops = 0;
        for (Map.Entry<Long, RegionConnector> entry : bestPerPair.entrySet()) {
            RegionConnector connector = entry.getValue();
            graph.allConnectors.add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionA(), k -> new ArrayList<>()).add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionB(), k -> new ArrayList<>()).add(connector);

            int hops = hopsPerPair.getOrDefault(entry.getKey(), 1);
            if (hops > 1) chainedCount++;
            maxHops = Math.max(maxHops, hops);
        }

        LOGGER.info("[Skavenblight] RegionGraph built: {} regions, {} connectors ({} chained, max {} hops)",
                regionIndex.getRegions().size(), graph.allConnectors.size(), chainedCount, maxHops);
        return graph;
    }

    private static void tryTrace(TerrainSnapshot snapshot, TerrainEvaluator evaluator, SiegeLineTracer lineTracer,
                                  RegionIndex regionIndex, FlowFieldState boundsState, Region fromRegion,
                                  BlockPos boundaryCell, int dx, int dy, int dz, int minKnownY, int maxKnownY,
                                  Map<Long, RegionConnector> bestPerPair, Map<Long, Integer> hopsPerPair) {

        List<SiegeNode> combinedOrderedSteps = new ArrayList<>();
        BlockPos currentAnchor = boundaryCell;
        int cost = 0;

        for (int hop = 1; hop <= MAX_CHAIN_HOPS; hop++) {
            SiegeLineTracer.TraceResult result = lineTracer.trace(snapshot, currentAnchor, dx, dy, dz, currentAnchor, cost,
                    pos -> evaluator.isOutOfBounds(snapshot, pos, boundsState), pos -> Integer.MAX_VALUE);

            if (!result.completed()) return; // genuine abort (out of bounds, invalid action, cost ceiling) - give up entirely

            combinedOrderedSteps.addAll(result.orderedSteps());
            cost = result.totalCost();

            Region toRegion = regionIndex.regionAt(result.endPos());
            if (toRegion != null && toRegion.getId() != fromRegion.getId()) {
                registerConnector(fromRegion, toRegion, boundaryCell, result.endPos(), cost, combinedOrderedSteps, hop, bestPerPair, hopsPerPair);
                return;
            }

            if (snapshot.getBlockState(result.endPos()).blocksMotion()) return; // tracer's synthetic landing can't be honored inside solid rock
            if (dy > 0 && result.endPos().getY() > maxKnownY + VERTICAL_CHAIN_SLACK) return;
            if (dy < 0 && result.endPos().getY() < minKnownY - VERTICAL_CHAIN_SLACK) return;

            // Landed in mid-air (or, degenerately, back inside the same region) - keep extending.
            currentAnchor = result.endPos();
        }
        // Hop cap exhausted without reaching a new region - no connector for this direction.
    }

    private static void registerConnector(Region fromRegion, Region toRegion, BlockPos boundaryCell, BlockPos endPos, int cost,
                                           List<SiegeNode> orderedSteps, int hops,
                                           Map<Long, RegionConnector> bestPerPair, Map<Long, Integer> hopsPerPair) {
        if (orderedSteps.isEmpty()) return;

        long pairKey = pairKey(fromRegion.getId(), toRegion.getId());
        RegionConnector existing = bestPerPair.get(pairKey);
        if (existing != null && existing.cost() <= cost) return;

        SiegeProject towardA = new SiegeProject(inboundInstructions(boundaryCell, orderedSteps), endPos, cost);
        SiegeProject towardB = new SiegeProject(outboundInstructions(boundaryCell, orderedSteps), boundaryCell, cost);
        RegionConnector connector = new RegionConnector(fromRegion.getId(), toRegion.getId(), boundaryCell, endPos, cost, towardA, towardB);
        bestPerPair.put(pairKey, connector);
        hopsPerPair.put(pairKey, hops);
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
