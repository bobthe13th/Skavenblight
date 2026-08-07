package org.ratden.skavenblight.ai.pathing.region;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.ratden.skavenblight.ai.pathing.FlowFieldState;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.PathStepEvaluator;
import org.ratden.skavenblight.ai.pathing.SiegeProject;
import org.ratden.skavenblight.ai.pathing.TerrainSnapshot;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Predicate;

/**
 * All regions + the connectors between them for one territory snapshot. Edges are discovered
 * by tracing from every region's boundary cells in the same 14 directions
 * SiegeProjectManager.evaluateMacroProjects already uses, keeping only the cheapest connector
 * found per region pair. Also absorbs RegionIndex's old job (the flat "which region contains this
 * position" lookup) as a private nested structure - RegionGraph is now the one place that answers
 * both "how do regions connect" and "which region is this cell in".
 */
public class RegionGraph {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[][] CARDINAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // 12 * TRACE_HOPS_PER_CHAIN(32) = 384 blocks, comfortably more than Minecraft's full
    // build-height range.
    private static final int MAX_CHAIN_HOPS = 12;
    // Ported from the old SiegeLineTracer.MAX_PROJECT_LENGTH: each of the 12 outer chain hops
    // above now walks up to this many single-block hops in a fixed direction (via
    // PathStepEvaluator.candidateSteps) before giving up on that direction - previously delegated
    // to one whole SiegeLineTracer.trace() call per outer iteration. Kept as its own constant
    // rather than flattened into one 384-hop loop, because VERTICAL_CHAIN_SLACK below is
    // calibrated in units of ONE OUTER hop (32 blocks, "one hop's worth of margin") - collapsing
    // the two loop levels would make that slack's own rationale meaningless (per-hop Y-checking is
    // strictly tighter than the coarse 32-block seam it's actually guarding).
    private static final int TRACE_HOPS_PER_CHAIN = 32;
    // One hop's worth of margin past the known region Y-range, so a landing exactly at a region's
    // edge isn't cut off early.
    private static final int VERTICAL_CHAIN_SLACK = 32;

    private final Map<Integer, List<RegionConnector>> connectorsByRegion = new HashMap<>();
    private final List<RegionConnector> allConnectors = new ArrayList<>();
    private final RegionLookup lookup;

    private RegionGraph(RegionLookup lookup) {
        this.lookup = lookup;
    }

    /** Test-only seam: builds a RegionGraph directly from known regions/connectors, bypassing the
     * real (unfakeable-in-a-plain-unit-test) TerrainSnapshot tracing in {@link #build}. Real
     * connector-discovery coverage is the Task 21-24 GameTest matrix - see RegionGraphTest's own
     * class doc for why this seam exists instead of a fuller build()-driven test. */
    static RegionGraph forTesting(List<Region> regions, List<RegionConnector> connectors) {
        RegionGraph graph = new RegionGraph(new RegionLookup(regions));
        for (RegionConnector connector : connectors) {
            graph.allConnectors.add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionA(), k -> new ArrayList<>()).add(connector);
            graph.connectorsByRegion.computeIfAbsent(connector.regionB(), k -> new ArrayList<>()).add(connector);
        }
        return graph;
    }

    public static RegionGraph build(TerrainSnapshot snapshot, List<Region> regions, Set<ChunkPos> bounds,
                                     BlockPos boundsAnchor, PathStepEvaluator evaluator) {
        RegionLookup lookup = new RegionLookup(regions);
        RegionGraph graph = new RegionGraph(lookup);
        FlowFieldState boundsState = new FlowFieldState(boundsAnchor, bounds);
        Predicate<BlockPos> outOfBounds = pos -> evaluator.isOutOfBounds(snapshot, pos, boundsState);

        // regionId pair -> cheapest connector found so far for that pair
        Map<Long, RegionConnector> bestPerPair = new HashMap<>();
        // regionId pair -> hop count the winning connector in bestPerPair took to discover
        Map<Long, Integer> hopsPerPair = new HashMap<>();
        // Every chunk any registerConnector call actually addCell'd a cell into, across the WHOLE
        // build - including chunks belonging to a candidate that was later superseded by a
        // cheaper one for the same region pair (registerConnector's addCell loop runs before the
        // "is this better than the existing candidate" short-circuit could matter - see that
        // method's own doc - so a superseded candidate's cells stay claimed and this set must
        // still include its chunks, or refreshChunks below would silently miss them). Accumulated
        // here, not re-derived from bestPerPair/allConnectors afterward, precisely so superseded
        // candidates aren't lost.
        Set<ChunkPos> touchedChunks = new HashSet<>();

        int minKnownY = regions.stream().mapToInt(r -> r.getMin().getY()).min().orElse(Integer.MIN_VALUE);
        int maxKnownY = regions.stream().mapToInt(r -> r.getMax().getY()).max().orElse(Integer.MAX_VALUE);

        for (Region region : regions) {
            for (BlockPos boundaryCell : region.getBoundaryCells()) {
                for (int dy : new int[]{-1, 1}) {
                    tryTrace(snapshot, evaluator, lookup, outOfBounds, region, boundaryCell, 0, dy, 0, minKnownY, maxKnownY, bestPerPair, hopsPerPair, touchedChunks);
                }
                for (int[] dir : CARDINAL_OFFSETS) {
                    for (int dy : new int[]{-1, 0, 1}) {
                        tryTrace(snapshot, evaluator, lookup, outOfBounds, region, boundaryCell, dir[0], dy, dir[1], minKnownY, maxKnownY, bestPerPair, hopsPerPair, touchedChunks);
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

        // registerConnector claims a connector's traced cells into both endpoint regions via
        // Region.addCell, mutating the SAME Region objects `lookup` was built from - but
        // RegionLookup (like the old RegionIndex it absorbs) takes no live view of a Region's
        // BitSet, only a one-time copy at construction. Re-stamping just the touched chunks here
        // keeps `lookup` current WITHOUT a second full from-scratch construction across the whole
        // territory - see RegionLookup.refreshChunks's own doc for why this preserves the exact
        // same shared-cell tie-break a full reconstruction would produce.
        lookup.refreshChunks(touchedChunks);

        LOGGER.info("[Skavenblight] RegionGraph built: {} regions, {} connectors ({} chained, max {} hops)",
                regions.size(), graph.allConnectors.size(), chainedCount, maxHops);
        return graph;
    }

    private static void tryTrace(TerrainSnapshot snapshot, PathStepEvaluator evaluator, RegionLookup lookup,
                                  Predicate<BlockPos> outOfBounds, Region fromRegion, BlockPos boundaryCell,
                                  int dx, int dy, int dz, int minKnownY, int maxKnownY,
                                  Map<Long, RegionConnector> bestPerPair, Map<Long, Integer> hopsPerPair,
                                  Set<ChunkPos> touchedChunks) {

        List<FlowStep> combinedOrderedSteps = new ArrayList<>();
        BlockPos currentAnchor = boundaryCell;
        int cost = 0;

        for (int hop = 1; hop <= MAX_CHAIN_HOPS; hop++) {
            TraceResult result = traceLine(snapshot, evaluator, currentAnchor, dx, dy, dz, outOfBounds);

            if (!result.completed()) return; // genuine abort - out of bounds, or no candidate exists in this exact direction

            combinedOrderedSteps.addAll(result.orderedSteps());
            cost += result.cost();

            Region toRegion = lookup.regionAt(result.endPos());
            if (toRegion != null && toRegion.getId() != fromRegion.getId()) {
                registerConnector(fromRegion, toRegion, boundaryCell, result.endPos(), cost, combinedOrderedSteps, hop, bestPerPair, hopsPerPair, touchedChunks);
                return;
            }

            // The old tracer's cap-hit landing was a SYNTHETIC BUILD_LANDING never itself checked
            // against real terrain - this abort (endPos still solid) is the one piece of that old
            // contract worth keeping under the new model too: a chain landing back inside solid
            // rock has nothing standable to continue extending from, whichever action produced it,
            // and there's no synthetic platform marker to paper over that anymore (PlatformInserter
            // is a pure post-process over a FINISHED build order, not something tryTrace inserts
            // mid-discovery).
            if (snapshot.getBlockState(result.endPos()).blocksMotion()) return;
            if (dy > 0 && result.endPos().getY() > maxKnownY + VERTICAL_CHAIN_SLACK) return;
            if (dy < 0 && result.endPos().getY() < minKnownY - VERTICAL_CHAIN_SLACK) return;

            // Landed in mid-air (or, degenerately, back inside the same region) - keep extending.
            currentAnchor = result.endPos();
        }
        // Hop cap exhausted without reaching a new region - no connector for this direction.
    }

    /** One "chain hop" worth of result: the ordered steps taken, where the chain ended up, its
     * total cost, and whether it completed (as opposed to a genuine abort - out of bounds, or no
     * candidate step exists in the exact fixed direction being traced). */
    private record TraceResult(List<FlowStep> orderedSteps, BlockPos endPos, int cost, boolean completed) {
        static TraceResult aborted() {
            return new TraceResult(List.of(), null, 0, false);
        }
    }

    /**
     * Replaces one {@code SiegeLineTracer.trace()} call: walks a fixed {@code (dx,dy,dz)} direction
     * up to {@link #TRACE_HOPS_PER_CHAIN} single-block hops via {@code PathStepEvaluator
     * .candidateSteps}, filtered to the exact offset each time (the same technique
     * {@code SiegeProject.traceChainedHops} uses for widening - mirrored here rather than shared
     * directly, since the two call shapes differ enough - a fresh long-distance discovery trace vs.
     * a build-order-length-bounded widen - that sharing would need a cross-package dependency for
     * no real benefit).
     *
     * <p>Terminates naturally the moment a WALK step is offered (real ground - either genuinely new
     * territory or a landing back on already-known ground), aborts the moment NO candidate exists in
     * the exact fixed direction (most commonly a pure-vertical {@code (dy!=0,dx==0,dz==0)} direction
     * against non-walkable terrain, since climbing offers no candidate there at all - see
     * {@code PathStepEvaluator.candidateSteps}), or, if {@link #TRACE_HOPS_PER_CHAIN} is exhausted
     * while still legitimately building, returns what's been collected so far as a SUCCESSFUL (not
     * aborted) result - the same cap-exhaustion-is-success contract
     * {@code SiegeProject.traceChainedHops} documents.
     *
     * <p>Cost is the sum of each hop's own {@code PathStepEvaluator.EvaluatedStep.cost()} - the
     * same real per-step cost model the ordinary flood and every other candidate comparison in this
     * rewrite already use, replacing the old {@code SiegeLineTracer}'s own bespoke
     * {@code buildingBasePenalty * COST_MULTIPLIER} + dy-biased-multiplier formula. That old formula
     * was a second, divergent cost model that existed only for macro-project line evaluation; the
     * design's whole point is ONE unified cost model everywhere, so it is dropped rather than
     * ported - flagged here explicitly since it's a real behavior change, not an oversight.
     */
    private static TraceResult traceLine(TerrainSnapshot snapshot, PathStepEvaluator evaluator, BlockPos anchor,
                                          int dx, int dy, int dz, Predicate<BlockPos> outOfBounds) {
        List<FlowStep> orderedSteps = new ArrayList<>();
        BlockPos current = anchor;
        int totalCost = 0;

        for (int i = 0; i < TRACE_HOPS_PER_CHAIN; i++) {
            BlockPos next = current.offset(dx, dy, dz);
            if (outOfBounds.test(next)) return TraceResult.aborted();

            List<PathStepEvaluator.EvaluatedStep> steps = evaluator.candidateSteps(snapshot, current, Collections.emptySet(), outOfBounds);
            PathStepEvaluator.EvaluatedStep matching = steps.stream()
                    .filter(s -> s.pos().equals(next)).findFirst().orElse(null);
            if (matching == null) return TraceResult.aborted(); // no valid step exists in this exact fixed direction

            totalCost += matching.cost();
            orderedSteps.add(new FlowStep(matching.pos(), matching.action(), current));

            if (matching.action() == PathAction.WALK) {
                return new TraceResult(orderedSteps, next, totalCost, true);
            }

            current = next;
        }

        return new TraceResult(orderedSteps, current, totalCost, true);
    }

    private static void registerConnector(Region fromRegion, Region toRegion, BlockPos boundaryCell, BlockPos endPos, int cost,
                                           List<FlowStep> orderedSteps, int hops,
                                           Map<Long, RegionConnector> bestPerPair, Map<Long, Integer> hopsPerPair,
                                           Set<ChunkPos> touchedChunks) {
        if (orderedSteps.isEmpty()) return;

        long pairKey = pairKey(fromRegion.getId(), toRegion.getId());
        RegionConnector existing = bestPerPair.get(pairKey);
        if (existing != null && existing.cost() <= cost) return;

        // exitPos is the far side relative to each orientation's own entryPos - see
        // SiegeProject.getExitPos's doc for why a fallback is seeded there instead of folded into
        // `instructions`. Needed because the destination-side endpoint of a chained connector is
        // claimed into its region via addCell just below (task-8), not genuine flood-fill
        // membership - that destination region's OWN Dijkstra pass can never independently reach a
        // cell it only owns because addCell said so, and neither outboundInstructions nor
        // inboundInstructions keys their own far endpoint (each assumes, usually correctly, that
        // the far region's own pass already covers it - see each method's own doc). Without this,
        // a mob that finishes crossing lands on a cell with NO instruction anywhere and is
        // permanently stuck (confirmed via testParentRegionGetsRealInstructionsForSharedConnectorCells
        // and the real-world testSingleRatBuildsStaircaseAcrossSmallGap).
        List<FlowStep> reversedSteps = new ArrayList<>(orderedSteps);
        Collections.reverse(reversedSteps);

        // Both orientations describe the same physical line (same blocks, opposite direction) -
        // one shared networkId represents that one underlying network, not two independent ones.
        // Whether this needs to be DETERMINISTIC (derived from regionA/regionB/boundaryCell/endPos,
        // so a persisted project can be matched back to a rediscovered connector after a territory
        // rebuild) is an open question for Task 15 (persistence) to settle - nothing reads
        // getNetworkId() yet, so there's no observable behavior riding on this choice today; not
        // built on a guess about what persistence needs.
        UUID networkId = UUID.randomUUID();

        SiegeProject towardA = new SiegeProject(inboundInstructions(boundaryCell, orderedSteps), reversedSteps, endPos,
                endPos, cost, boundaryCell, networkId);
        SiegeProject towardB = new SiegeProject(outboundInstructions(boundaryCell, orderedSteps), orderedSteps, boundaryCell,
                boundaryCell, cost, endPos, networkId);

        // Claim every cell this connector actually traced into BOTH endpoint regions' own
        // membership, right here at graph-build time - not just at the two regions' boundary
        // cells. A region's BitSet otherwise only grows via a full rebuild or a dirty-region
        // rescan of that region's OWN prior bounding box (see RegionScanner.floodFill), and a
        // long chained connector's midpoint can sit outside BOTH endpoints' natural flood-fill
        // bounds indefinitely - getRegionFlowFieldFor resolves a region FIRST, so a mob standing
        // on such a cell mid-crossing would otherwise fail that lookup and fall back to
        // wilderness/StrandedGoal even though the connector's own instructions are sitting right
        // there. Both regions claiming the SAME physical cells is intentional and harmless: either
        // region answering "yes, I contain this cell" is exactly what makes the lookup succeed,
        // and this only runs once per discovered connector, not per Dijkstra step, so it isn't a
        // hot-path cost. Simplest correct implementation - just call the existing addCell and
        // accept the cells becoming permanent region members even if a later, cheaper connector
        // for the same region pair supersedes this one in bestPerPair; only a full rebuild would
        // ever re-partition them.
        for (FlowStep step : orderedSteps) {
            fromRegion.addCell(step.pos());
            toRegion.addCell(step.pos());
            touchedChunks.add(new ChunkPos(step.pos()));
        }

        RegionConnector connector = new RegionConnector(fromRegion.getId(), toRegion.getId(), boundaryCell, endPos, cost, towardA, towardB);
        bestPerPair.put(pairKey, connector);
        hopsPerPair.put(pairKey, hops);
    }

    /**
     * Instruction map for crossing a traced line in the OUTWARD direction: from {@code anchor}
     * (a boundary cell of the region that discovered the line) to the far end. Each key is a
     * position a mob can occupy, mapped to a FlowStep whose {@code pos()} redundantly repeats that
     * same key (matching FlowFieldCalculator's own nextInstructionMap convention) and whose
     * {@code predecessorPos()} carries the actually-useful "next hop toward the far end" - the same
     * field-repacking rule Task 6 established for the ordinary flood, applied here since
     * {@code orderedSteps}' own entries are direction-neutral (each one's {@code pos()} is simply
     * "the position this hop reaches", {@code predecessorPos()} unused by
     * {@code SiegeProject.planSteps}, which derives predecessor from list order/anchor instead - see
     * that method).
     *
     * <p>Deliberately NOT reusing an orderedSteps entry as-is for the map value: doing so would put
     * the WRONG position in the value's own {@code pos()} field (the step's own destination, not the
     * map key/"where a mob is now"), silently breaking any future consumer that reads
     * {@code value.pos()} expecting it to equal its own map key the way {@code FlowFieldCalculator}
     * -produced entries always do.
     */
    private static Map<BlockPos, FlowStep> outboundInstructions(BlockPos anchor, List<FlowStep> steps) {
        Map<BlockPos, FlowStep> map = new HashMap<>();
        BlockPos from = anchor;
        for (FlowStep step : steps) {
            map.put(from, new FlowStep(from, step.action(), step.pos()));
            from = step.pos();
        }
        return map;
    }

    /**
     * Mirror image of {@link #outboundInstructions}: crossing the same line INWARD, from its far end
     * back to {@code anchor}. The anchor is walkable ground by construction (it's a region boundary
     * cell the trace started from), so the last hop into it is a plain WALK.
     */
    private static Map<BlockPos, FlowStep> inboundInstructions(BlockPos anchor, List<FlowStep> steps) {
        Map<BlockPos, FlowStep> map = new HashMap<>();
        for (int i = steps.size() - 1; i >= 0; i--) {
            BlockPos from = steps.get(i).pos();
            BlockPos to = (i == 0) ? anchor : steps.get(i - 1).pos();
            PathAction action = (i == 0) ? PathAction.WALK : steps.get(i - 1).action();
            map.put(from, new FlowStep(from, action, to));
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

    public Region regionAt(BlockPos pos) {
        return lookup.regionAt(pos);
    }

    public Integer regionIdAt(BlockPos pos) {
        return lookup.regionIdAt(pos);
    }

    public List<Region> getRegions() {
        return lookup.getRegions();
    }

    /**
     * Republishes this graph's connectors against a freshly-rescanned region list - used by
     * {@code TerritoryRegionMap}'s steady-state dirty-region fast path, which replaces region
     * MEMBERSHIP but never re-traces connectors. Returns a NEW instance (never mutates {@code
     * this}) so callers relying on object identity to detect "a fresh lookup was published" -
     * mirroring the old {@code RegionIndex}'s own two-assignment-site contract (full rebuild AND
     * steady-state dirty rescan each produced a new instance) - keep working now that RegionIndex's
     * lookup job lives here instead.
     */
    public RegionGraph withUpdatedRegions(List<Region> updatedRegions) {
        RegionGraph refreshed = new RegionGraph(new RegionLookup(updatedRegions));
        refreshed.allConnectors.addAll(this.allConnectors);
        for (Map.Entry<Integer, List<RegionConnector>> entry : this.connectorsByRegion.entrySet()) {
            refreshed.connectorsByRegion.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return refreshed;
    }

    /**
     * Diagnostic accessor ONLY - per this task's own correction to the originally-approved design,
     * {@link #build} always offers every {@code PathStepEvaluator.candidateSteps} candidate at its
     * real cost (bedrock-tier mining included) with no gating flag and no second pass, so there is
     * no retry left to trigger off of this. Every region id absent from {@code routeTree} after a
     * normal build+compute is genuinely, confirmedly unreachable - used for logging/observability
     * ("this territory has N isolated regions"), never for deciding whether an edge is offered.
     */
    public Set<Integer> confirmedUnreachableRegionIds(int rootRegionId, RegionRouteTree routeTree) {
        Set<Integer> unreachable = new HashSet<>();
        for (Region region : lookup.getRegions()) {
            if (!routeTree.isReachable(region.getId())) {
                unreachable.add(region.getId());
            }
        }
        return unreachable;
    }

    /**
     * O(1) "which region contains this position" lookup, absorbed from the old standalone
     * RegionIndex class. Built once after a RegionScanner pass by stamping a per-chunk regionId
     * array, mirroring Region's own per-chunk BitSet layout.
     */
    private static final class RegionLookup {
        private final List<Region> regions;
        private final Map<ChunkPos, int[]> regionIdByCell = new HashMap<>();
        private final int minBuildHeight;
        private final int height;

        RegionLookup(List<Region> regions) {
            this.regions = new ArrayList<>(regions);
            this.minBuildHeight = regions.isEmpty() ? 0 : regions.get(0).getMinBuildHeight();
            this.height = regions.isEmpty() ? 0 : regions.get(0).getHeight();

            for (Region region : regions) {
                for (Map.Entry<ChunkPos, BitSet> entry : region.getChunkCells().entrySet()) {
                    int[] ids = regionIdByCell.computeIfAbsent(entry.getKey(), c -> {
                        int[] arr = new int[16 * 16 * height];
                        Arrays.fill(arr, -1);
                        return arr;
                    });
                    BitSet bits = entry.getValue();
                    for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                        ids[i] = region.getId();
                    }
                }
            }
        }

        Region regionAt(BlockPos pos) {
            Integer id = regionIdAt(pos);
            if (id == null) return null;
            for (Region region : regions) {
                if (region.getId() == id) return region;
            }
            return null;
        }

        Integer regionIdAt(BlockPos pos) {
            int localY = pos.getY() - minBuildHeight;
            if (height == 0 || localY < 0 || localY >= height) return null;

            int[] ids = regionIdByCell.get(new ChunkPos(pos));
            if (ids == null) return null;

            int localX = pos.getX() & 15;
            int localZ = pos.getZ() & 15;
            int id = ids[(localX * 16 + localZ) * height + localY];
            return id == -1 ? null : id;
        }

        List<Region> getRegions() {
            return regions;
        }

        /**
         * Re-stamps this lookup's flat per-chunk arrays for exactly {@code changedChunks}, using
         * the SAME "iterate {@link #regions} in list order, last matching region wins" semantics
         * the constructor above uses - not a per-cell overwrite in whatever order the caller
         * happens to process cells in, which would silently change which region wins a
         * shared-cell tie-break (see {@code RegionGraph.registerConnector}: both a connector's
         * endpoint regions legitimately claim the SAME cells).
         *
         * <p>The one caller is {@code RegionGraph.build}, immediately after its own
         * {@code registerConnector} calls have finished mutating {@code Region.addCell} on this
         * same {@link #regions} list - avoids a second full {@code new RegionLookup(regions)}
         * construction, which for a territory with many occupied chunks is a real, avoidable
         * per-chunk {@code int[16*16*height]} allocation repeated a second time for chunks a
         * connector never even touched.
         */
        void refreshChunks(Collection<ChunkPos> changedChunks) {
            if (height == 0 || changedChunks.isEmpty()) return;
            for (ChunkPos chunk : changedChunks) {
                int[] ids = regionIdByCell.computeIfAbsent(chunk, c -> new int[16 * 16 * height]);
                Arrays.fill(ids, -1);
                for (Region region : regions) {
                    BitSet bits = region.getChunkCells().get(chunk);
                    if (bits == null) continue;
                    for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                        ids[i] = region.getId();
                    }
                }
            }
        }
    }
}
