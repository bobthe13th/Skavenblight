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
    // normally harmless, but if two independently-produced chains (e.g. a persisted region
    // connector's own instructions and a plain terrain-driven line evaluated nearby) each end up
    // writing an adjacent pair of positions that point AT EACH OTHER, the result is a direct
    // 2-cell cycle: a mob standing on either cell gets shuttled back and forth forever and never
    // reaches a real build/walk step. Confirmed via a live dump (2026-07-30): clanrats piling up
    // directly under a floating nexus, FollowFlowFieldGoal ping-ponging between two positions one
    // block apart in Y, matching PathingDebugFileWriter's own "LOOP DETECTED - revisited X" trace.
    // Rather than chase every possible producer of a bad pair (multiple have already been found
    // and partially fixed in this exact connector/macro-project area - see CLAUDE.md gotchas),
    // enforce the tree invariant once, here, at the single point every source's output converges,
    // right before publishing. Logs full detail (which side was locked to a project) so the next
    // occurrence's server log pinpoints the actual producer instead of requiring another manual
    // dump autopsy.
    private void breakMutualCycles() {
        Set<BlockPos> lockedPositions = projectManager.getLockedPositions();

        for (BlockPos pos : detectMutualCyclePositions(nextInstructionMap)) {
            SiegeNode node = nextInstructionMap.get(pos);
            LOGGER.warn("[Pathfinder] Broke mutual flow-field cycle: {} ({}, locked={}) pointed at {} - "
                            + "dropped so mobs fall back to local breach instead of looping forever",
                    pos.toShortString(), node.action(), lockedPositions.contains(pos), node.pos().toShortString());

            nextInstructionMap.remove(pos);
            nextCostMap.remove(pos);
        }
    }

    /**
     * Finds every position in {@code instructionMap} that forms a direct A-points-to-B,
     * B-points-right-back-to-A cycle. Split out from {@link #breakMutualCycles()} as a pure,
     * dependency-free function (no logging, no locked-position lookup, no instance state) so it's
     * unit-testable against a plain fixture map instead of requiring a fully-constructed
     * FlowFieldCalculator - see FlowFieldCalculatorTest.
     */
    static Set<BlockPos> detectMutualCyclePositions(Map<BlockPos, SiegeNode> instructionMap) {
        Set<BlockPos> handled = new HashSet<>();
        Set<BlockPos> cyclePositions = new HashSet<>();

        for (Map.Entry<BlockPos, SiegeNode> entry : instructionMap.entrySet()) {
            BlockPos pos = entry.getKey();
            if (handled.contains(pos)) continue;

            BlockPos next = entry.getValue().pos();
            if (next.equals(pos)) continue; // a region's own local Dijkstra objective self-references - expected, not a cycle

            SiegeNode nextsInstruction = instructionMap.get(next);
            if (nextsInstruction == null || !nextsInstruction.pos().equals(pos)) continue;

            handled.add(pos);
            handled.add(next);
            cyclePositions.add(pos);
            cyclePositions.add(next);
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
