package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.Config;
import org.slf4j.Logger;

import java.util.*;

public class FlowFieldCalculator {

    private static final Logger LOGGER = LogUtils.getLogger();

    // How many consecutive MINE steps the core Dijkstra step (processOrthogonalNeighbors) may
    // chain before a branch is abandoned. Mirrors SiegeProjectManager's own mineProjectLength
    // cap (also 5) for its macro-project line tracer - without an equivalent cap here, the core
    // search has no boundary telling it "this is just solid rock, stop": it will tunnel one
    // MINE-step at a time arbitrarily deep into undisturbed stone looking for marginal cost
    // savings, especially now that TerrainSnapshot covers the full vertical column instead of a
    // narrow band. Observed in practice: 69535 of 114369 nodes (61%) were MINE in a single pass
    // that ran 14+ minutes without finishing. When the core search hits this cap, evaluateMacroProjects
    // (already triggered via hitObstacle) is the existing, deliberate fallback for longer connections.
    private static final int MAX_CONSECUTIVE_MINE_DEPTH = 5;

    // Minimum real time between liveDebugMap publishes. Was previously "every 50 nodes"
    // regardless of map size - an O(n^2) cost over a full pass (~2287 full-map copies for a
    // 114k-node pass, copying a map that itself grows to 114k entries) that scales directly with
    // total node count and became catastrophic once Config.maxFlowFieldNodes was raised well
    // past its default. This snapshot only feeds the debug visualizer/dump, which never needs
    // more than a few updates per second regardless of how large the map has grown.
    private static final long LIVE_DEBUG_PUBLISH_INTERVAL_MS = 250;

    private PriorityQueue<QueueNode> calcQueue;
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();
    // Consecutive-MINE-steps-to-reach-this-position, keyed the same as nextCostMap/
    // nextInstructionMap and always updated alongside them so it's consistent with whichever
    // path is currently cheapest to a given position. Absent (default 0) is correct for any
    // position reached via a macro-project injection rather than a core step, since a project's
    // entry point represents standing on solid ground after the project completes - the same as
    // a WALK/BUILD_* step, not a mid-tunnel MINE chain.
    private final Map<BlockPos, Integer> mineChainDepth = new HashMap<>();
    private long lastLiveDebugPublishMs = 0;

    // Diagnostics for the LAST completed pass, read by PathingDebugFileWriter/StandardFlowField's
    // log line. nextCostMap.size() (not nextInstructionMap.size(), which is what "Total Nodes"
    // in the finished-pass log/dump actually reports) is the value the budget cutoff at line
    // ~96 checks - a single successful macro-project line only costs ONE nextCostMap entry (its
    // endpoint) while writing up to MAX_PROJECT_LENGTH (32) entries into nextInstructionMap via
    // putAll, so "Total Nodes" can look enormous (tens of thousands) while nextCostMap - the
    // actual budget-gated counter real WALK propagation is competing against - is quietly maxed
    // out. Without surfacing this separately there was no way to tell "budget genuinely
    // exhausted by real competition for slots" apart from "queue drained naturally" from outside
    // this class.
    private int lastPassNodeCount = 0;
    private boolean lastPassBudgetExhausted = false;

    // Volatile immutable map reference for atomic, zero-flicker snapshot reads across threads
    private volatile Map<BlockPos, SiegeNode> liveDebugMap = Collections.emptyMap();

    // Positions dropped by breakMutualCycles() on the immediately preceding pass, so a still-
    // recurring producer bug doesn't spam a fresh WARN every single recompute (this class's own
    // recompute cadence is gated by TerritoryRegionMap's 80-tick/~4s cooldown, but that's still
    // often enough to flood the log for as long as the underlying bug persists). Replaced
    // wholesale each pass rather than merged/grown, so a bug whose shape changes (a different
    // position now affected) is still surfaced, while an unchanged repeat goes quiet.
    private Set<BlockPos> lastPassWarnedPositions = Collections.emptySet();

    private final TerrainEvaluator terrainEvaluator;
    private final SiegeProjectManager projectManager;
    private final CalculationThrottler throttler;

    public FlowFieldCalculator(TerrainEvaluator evaluator, SiegeProjectManager manager, CalculationThrottler throttler) {
        this.terrainEvaluator = evaluator;
        this.projectManager = manager;
        this.throttler = throttler;
    }

    /**
     * Returns an unmodifiable atomic snapshot of live calculation progress.
     */
    public Map<BlockPos, SiegeNode> getLiveDebugMap() {
        return this.liveDebugMap;
    }

    /**
     * Runs the full Dijkstra pass against {@code terrain}, throttled by the live MSPT-based
     * budget - see the 3-arg overload's doc for when that's the wrong choice.
     */
    public void calculateFully(TerrainAccess terrain, FlowFieldState state) {
        calculateFully(terrain, state, true);
    }

    /**
     * Runs the full Dijkstra pass against {@code terrain}. In normal use this is a
     * TerrainSnapshot captured just before this method was kicked off on a background
     * thread - this method itself has no thread affinity, it's only as safe as whatever
     * TerrainAccess it's given. Never pass a LiveTerrainAccess here from a background
     * thread.
     *
     * @param respectThrottle true for the steady-state per-region recompute path
     * (TerritoryRegionMap.recomputeDirtyRegions), where the calculation genuinely repeats
     * under whatever server load exists at the time and CalculationThrottler's live MSPT
     * budget is doing its intended job. False for the one-shot initial/topology-change
     * rebuild (TerritoryRegionMap.rebuildRegionsAndGraph): that pass runs exactly once at
     * world-join or territory change, competing with unrelated transient MSPT spikes (chunk
     * loading, resource/model loading) that have nothing to do with this calculation's own
     * cost - and unlike the steady-state path, there is no "next pass" to make up a region
     * left permanently truncated by a throttle floor hit on this single chance. Confirmed in
     * testing: the very first rebuild after world-join hit the throttle's MIN_THROTTLE_FRACTION
     * floor (2250 of 25000 configured nodes) before the ground region's ~10000-cell pass could
     * finish, leaving the large majority of its cells with no instruction - every clanrat outside
     * the truncated radius fell back to wilderness wandering until an unrelated block change
     * happened to trigger a full recompute later, by which point the throttle had recovered.
     */
    public void calculateFully(TerrainAccess terrain, FlowFieldState state, boolean respectThrottle) {
        startCalculation(terrain, state);
        processCalculationQueue(terrain, state, respectThrottle);
    }

    private void startCalculation(TerrainAccess terrain, FlowFieldState state) {
        nextCostMap.clear();
        nextInstructionMap.clear();
        mineChainDepth.clear();
        liveDebugMap = Collections.emptyMap();
        lastLiveDebugPublishMs = System.currentTimeMillis();

        calcQueue = new PriorityQueue<>();
        BlockPos targetPos = state.getTargetPos();

        calcQueue.add(new QueueNode(targetPos, 0));
        nextCostMap.put(targetPos, 0);
        nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));

        projectManager.injectActiveProjects(terrain, calcQueue, nextCostMap, nextInstructionMap);
    }

    private void processCalculationQueue(TerrainAccess terrain, FlowFieldState state, boolean respectThrottle) {
        // Dynamically throttle max allowed nodes per calculation using MSPT metric - unless this
        // is the one-shot rebuild pass, which gets the full configured budget regardless of
        // current throttle fraction (see the 3-arg calculateFully's doc for why).
        int maxAllowedNodes = respectThrottle
                ? Math.min(Config.maxFlowFieldNodes, throttler.getNodesPerTick())
                : Config.maxFlowFieldNodes;
        lastPassBudgetExhausted = false;

        while (!calcQueue.isEmpty()) {
            if (nextCostMap.size() >= maxAllowedNodes) {
                lastPassBudgetExhausted = true;
                calcQueue.clear();
                break;
            }

            // Atomically publish a progress snapshot without clearing the active map, throttled
            // by wall-clock time rather than node count so cost doesn't scale with map size.
            long now = System.currentTimeMillis();
            if (now - lastLiveDebugPublishMs >= LIVE_DEBUG_PUBLISH_INTERVAL_MS) {
                this.liveDebugMap = Map.copyOf(nextInstructionMap);
                lastLiveDebugPublishMs = now;
            }

            QueueNode qNode = calcQueue.poll();
            BlockPos current = qNode.pos();
            int currentCost = qNode.cost();

            if (currentCost > nextCostMap.getOrDefault(current, Integer.MAX_VALUE)) continue;

            boolean hitObstacle = processOrthogonalNeighbors(terrain, current, currentCost, state);

            SiegeNode currentInstruction = nextInstructionMap.get(current);
            boolean isPlannedLanding = currentInstruction != null && currentInstruction.action() == SiegeNode.SiegeAction.BUILD_LANDING;

            if (hitObstacle && (terrainEvaluator.isWalkableTerrain(terrain, current) || current.equals(state.getTargetPos()) || isPlannedLanding)) {
                projectManager.evaluateMacroProjects(terrain, current, state, currentCost, calcQueue, nextCostMap, nextInstructionMap);
            }
        }

        finalizeCalculation(state);
    }

    private boolean processOrthogonalNeighbors(TerrainAccess terrain, BlockPos current, int currentCost, FlowFieldState state) {
        List<TerrainEvaluator.EvaluatedStep> validSteps = terrainEvaluator.getValidOrthogonalSteps(terrain, current, projectManager.getLockedPositions(), state);
        int currentMineDepth = mineChainDepth.getOrDefault(current, 0);

        for (TerrainEvaluator.EvaluatedStep step : validSteps) {
            // Reset to 0 on WALK/BUILD_* (standing on solid ground); only MINE chains deeper.
            // Beyond the cap, this branch is abandoned - see MAX_CONSECUTIVE_MINE_DEPTH.
            int stepMineDepth = step.action() == SiegeNode.SiegeAction.MINE ? currentMineDepth + 1 : 0;
            if (stepMineDepth > MAX_CONSECUTIVE_MINE_DEPTH) continue;

            int totalCost = currentCost + step.cost();

            if (totalCost < nextCostMap.getOrDefault(step.pos(), Integer.MAX_VALUE)) {
                nextCostMap.put(step.pos(), totalCost);
                nextInstructionMap.put(step.pos(), new SiegeNode(current, step.action()));
                mineChainDepth.put(step.pos(), stepMineDepth);
                calcQueue.add(new QueueNode(step.pos(), totalCost));
            }
        }

        // Was: only same-Y WALK steps counted. On any natural sloped/uneven terrain, a
        // neighbor one block down or up is a perfectly ordinary, already-handled MINE or
        // BUILD_PILLAR/BUILD_STAIR single step (see getValidOrthogonalSteps) - it never gets
        // counted as WALK even though the core Dijkstra step has a cheap, correct way to take
        // it right here, with no macro-project chain needed. That made hitObstacle fire on
        // nearly every tile that wasn't a perfectly flat 4-way intersection (documented in
        // CLAUDE.md as the known "sunburst" cause), not just genuine multi-block gaps/dead
        // ends - confirmed in testing via 1948 macro-evaluation triggers and 486623 line-steps
        // in a single pass, while WALK coverage stayed flat around 6600 nodes pass after pass.
        // Counting any offered step (any action, any dy) reflects what actually needs a macro
        // project: a direction getValidOrthogonalSteps found NOTHING for at all (a gap deeper
        // than the single-block drop it already handles, or a solid wall in every dy).
        long walkableNeighbors = validSteps.size();

        return walkableNeighbors < 4;
    }

    private void finalizeCalculation(FlowFieldState state) {
        breakMutualCycles();

        // Atomic snapshot update
        this.liveDebugMap = Map.copyOf(nextInstructionMap);

        lastPassNodeCount = nextCostMap.size();

        state.updateInstructions(new HashMap<>(nextInstructionMap));
        projectManager.finalizeCandidateProjects(nextCostMap, state.getInstructionMap());
    }

    // Every ordinary core-flood write (processOrthogonalNeighbors) is gated by
    // "totalCost < nextCostMap.getOrDefault(...)", which guarantees a monotonically-improving,
    // acyclic predecessor tree rooted at the target - the same guarantee any correct Dijkstra
    // gives. Project-instruction writes (SiegeProjectManager.injectActiveProjects/
    // evaluateSingleLine) bypass that guard entirely: they write whole chains of positions
    // straight into nextInstructionMap with no cost comparison, because a macro-project's
    // positions usually don't have a competing nextCostMap entry to compare against yet. That's
    // normally harmless, but if independently-produced chains (e.g. a persisted region
    // connector's own instructions and a plain terrain-driven line evaluated nearby) end up
    // writing a loop of positions that point back around at each other, the result is a cycle: a
    // mob standing on any cell in the loop gets shuttled around it forever and never reaches a
    // real build/walk step. Confirmed via a live dump (2026-07-30): clanrats piling up directly
    // under a floating nexus, FollowFlowFieldGoal ping-ponging between two positions one block
    // apart in Y, matching PathingDebugFileWriter's own "LOOP DETECTED - revisited X" trace.
    // Rather than chase every possible producer of a bad chain (multiple have already been found
    // and partially fixed in this exact connector/macro-project area - see CLAUDE.md gotchas),
    // enforce a no-cycle invariant here, right before publishing, against THIS region's own
    // nextInstructionMap only. That's a real, but limited, guarantee: a cycle spanning two
    // different regions' own separately-computed flow fields (each with its own
    // FlowFieldCalculator/nextInstructionMap) is NOT detected here and would need cross-region
    // tracing to catch - an explicit, accepted scope limit, not something this method attempts.
    // Only ONE position per detected cycle is dropped (not the whole loop): removing any single
    // node breaks the cycle, since whatever still points at the dropped node now points at
    // nothing and falls back to a local breach/wilderness instead of continuing to loop - see
    // pickCyclePositionToDrop for which one. Logs full detail (which side was kept/dropped and
    // why) so the next occurrence's server log pinpoints the actual producer instead of requiring
    // another manual dump autopsy; deduplicated against the previous pass (lastPassWarnedPositions)
    // so a persisting bug doesn't spam a fresh WARN every single recompute.
    private void breakMutualCycles() {
        Set<BlockPos> lockedPositions = projectManager.getLockedPositions();
        Set<BlockPos> cyclePositions = detectMutualCyclePositions(nextInstructionMap);

        if (cyclePositions.isEmpty()) {
            lastPassWarnedPositions = Collections.emptySet();
            return;
        }

        Set<BlockPos> grouped = new HashSet<>();
        Set<BlockPos> warnedThisPass = new HashSet<>();

        for (BlockPos start : cyclePositions) {
            if (grouped.contains(start)) continue;

            // Recover this individual cycle's own membership by walking it from `start` back
            // around to `start` - detectMutualCyclePositions only guarantees set membership, not
            // grouping, but cycles are vertex-disjoint (every position has exactly one outgoing
            // instruction, so following it from any cycle member stays within that same cycle
            // until it loops back to where the walk began).
            List<BlockPos> cycle = new ArrayList<>();
            BlockPos current = start;
            do {
                cycle.add(current);
                grouped.add(current);
                current = nextInstructionMap.get(current).pos();
            } while (!current.equals(start));

            BlockPos toDrop = pickCyclePositionToDrop(cycle, nextCostMap, lockedPositions);
            SiegeNode droppedNode = nextInstructionMap.get(toDrop);
            boolean singleLockedPreference = cycle.stream().filter(lockedPositions::contains).count() == 1;

            warnedThisPass.add(toDrop);
            if (!lastPassWarnedPositions.contains(toDrop)) {
                LOGGER.warn("[Pathfinder] Broke flow-field cycle of {} position(s): dropped {} ({}, locked={}) "
                                + "which pointed at {}, kept the other {} position(s) - reason: {} - "
                                + "dropped so mobs fall back to local breach instead of looping forever",
                        cycle.size(), toDrop.toShortString(), droppedNode.action(), lockedPositions.contains(toDrop),
                        droppedNode.pos().toShortString(), cycle.size() - 1,
                        singleLockedPreference
                                ? "kept the sole locked position in this cycle, dropped a non-locked one"
                                : "dropped the higher-cost position (or, on an absent/tied cost, the one encountered later while walking the cycle)");
            }

            nextInstructionMap.remove(toDrop);
            nextCostMap.remove(toDrop);
        }

        lastPassWarnedPositions = warnedThisPass;
    }

    /**
     * Decides which single position within one already-identified cycle should be dropped to
     * break it. Removing any one node from a cycle is sufficient - see {@link #breakMutualCycles()}
     * - so this only needs to pick the least-bad one to sacrifice:
     * <ol>
     *     <li>if exactly one of the cycle's positions is locked (an active SiegeProject's own
     *     claimed cell - see {@code SiegeProjectManager.getLockedPositions}), keep it and drop
     *     from the rest: the core flood skips locked positions entirely, so a locked cell dropped
     *     here could never be refilled by a later pass.</li>
     *     <li>otherwise, keep whichever position is cheaper in {@code costMap} and drop the
     *     higher-cost one. A macro-project chain's interior positions legitimately have no
     *     {@code costMap} entry at all (see {@code nextCostMap}'s own field doc) - an absent cost
     *     is treated the same as a tie. On a tie (or an absent cost on either side), drop
     *     whichever position was encountered LATER while walking the cycle, keeping the first one
     *     encountered - an arbitrary but deterministic tie-break.</li>
     * </ol>
     * Split out as a pure, dependency-free static function so it's unit-testable without a
     * fully-constructed FlowFieldCalculator - see FlowFieldCalculatorTest.
     *
     * @param cyclePositions this cycle's own members, in the order encountered while walking it
     *                        (see {@link #breakMutualCycles()}); must contain at least one entry.
     */
    static BlockPos pickCyclePositionToDrop(List<BlockPos> cyclePositions, Map<BlockPos, Integer> costMap,
                                             Set<BlockPos> lockedPositions) {
        List<BlockPos> candidates = cyclePositions;
        long lockedCount = cyclePositions.stream().filter(lockedPositions::contains).count();
        if (lockedCount == 1) {
            candidates = new ArrayList<>(cyclePositions);
            candidates.removeIf(lockedPositions::contains);
        }

        BlockPos worst = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            BlockPos candidate = candidates.get(i);
            Integer candidateCost = costMap.get(candidate);
            Integer worstCost = costMap.get(worst);

            // Absent or equal cost -> can't meaningfully compare, so the later-encountered
            // position wins (replaces `worst`, and so becomes the one that ends up dropped);
            // otherwise the strictly higher-cost position replaces `worst`.
            boolean candidateIsWorseOrTied = candidateCost == null || worstCost == null
                    || candidateCost.equals(worstCost) || candidateCost > worstCost;
            if (candidateIsWorseOrTied) {
                worst = candidate;
            }
        }

        return worst;
    }

    /**
     * Finds every position in {@code instructionMap} that is part of a cycle of ANY length -
     * reachable by repeatedly following each position's own {@code .pos()} - within this one map.
     * Only this region's own {@code nextInstructionMap} is ever visible here: a cycle spanning two
     * different regions' own separately-computed instruction maps is out of scope and NOT
     * detected (see {@link #breakMutualCycles()}'s doc). Split out from {@link #breakMutualCycles()}
     * as a pure, dependency-free function (no logging, no locked-position lookup, no instance
     * state) so it's unit-testable against a plain fixture map instead of requiring a
     * fully-constructed FlowFieldCalculator - see FlowFieldCalculatorTest.
     * <p>
     * Walks forward from each not-yet-resolved position, tracking this walk's own path in visited
     * order (the same revisited-position idea {@code PathingDebugFileWriter.writeMobPathTraces}
     * already uses to trace a single mob's path), stopping at whichever comes first:
     * <ul>
     *     <li>a self-reference ({@code next.equals(pos)}) - this region's own local Dijkstra
     *     objective; the expected end of a chain, not a cycle.</li>
     *     <li>a position with no entry in the map - a dead end.</li>
     *     <li>a position already visited earlier in THIS walk - closes a cycle; everything from
     *     that earlier visit through the end of the current path is the cycle. Anything visited
     *     before that (a straight run-up into the cycle) is NOT part of it.</li>
     *     <li>a position already fully resolved by an earlier walk - nothing new to add; either
     *     it was already recorded as part of a cycle then, or it's known to lead somewhere that
     *     isn't one.</li>
     * </ul>
     * Every position is walked into at most once before being marked resolved, so this stays
     * O(n) overall despite the outer loop touching every map entry.
     */
    static Set<BlockPos> detectMutualCyclePositions(Map<BlockPos, SiegeNode> instructionMap) {
        Set<BlockPos> cyclePositions = new HashSet<>();
        Set<BlockPos> resolved = new HashSet<>();

        for (BlockPos start : instructionMap.keySet()) {
            if (resolved.contains(start)) continue;

            List<BlockPos> path = new ArrayList<>();
            Map<BlockPos, Integer> indexInPath = new HashMap<>();
            BlockPos current = start;

            while (true) {
                if (resolved.contains(current)) break; // reached an earlier walk's territory - nothing new here

                Integer seenAt = indexInPath.get(current);
                if (seenAt != null) {
                    // Closed a loop back to a position already visited THIS walk - everything from
                    // there to the end of the path is the cycle; anything before it was a run-up.
                    cyclePositions.addAll(path.subList(seenAt, path.size()));
                    break;
                }

                SiegeNode node = instructionMap.get(current);
                if (node == null) break; // dead end - no instruction here at all

                BlockPos next = node.pos();
                if (next.equals(current)) break; // this region's own local Dijkstra objective - expected, not a cycle

                indexInPath.put(current, path.size());
                path.add(current);
                current = next;
            }

            resolved.addAll(path);
        }

        return cyclePositions;
    }

    /** Size of nextCostMap at the end of the last pass - the real budget-gated counter, distinct from the published instruction map's size (see field doc above). */
    public int getLastPassNodeCount() { return this.lastPassNodeCount; }

    /** True if the last pass ended by hitting its node budget rather than draining the queue naturally. */
    public boolean isLastPassBudgetExhausted() { return this.lastPassBudgetExhausted; }

    public record QueueNode(BlockPos pos, int cost) implements Comparable<QueueNode> {
        @Override
        public int compareTo(QueueNode o) {
            return Integer.compare(this.cost, o.cost);
        }
    }
}
