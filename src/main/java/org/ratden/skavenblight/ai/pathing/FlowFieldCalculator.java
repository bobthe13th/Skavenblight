package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.Config;
import org.slf4j.Logger;

import java.util.*;

public class FlowFieldCalculator {

    private static final Logger LOGGER = LogUtils.getLogger();

    // How many of a cell's candidateSteps must be real WALK steps before this class stops ALSO
    // trying a macro-project search from it, on top of the ordinary per-cell candidates the core
    // flood already relaxes. Counting ALL candidates regardless of action - what the old
    // split-evaluator version did, specifically to fix a documented "sunburst" false-trigger bug -
    // would be meaningless under the unified PathStepEvaluator: candidateSteps ALWAYS offers a
    // construction candidate for every direction that fails the WALK check (frontier gating - see
    // PathStepEvaluator's own class doc), so counting any-action candidates would almost never dip
    // below any threshold except at a genuine dead end (out-of-bounds/locked in every direction).
    // Counting WALK-only mirrors RegionScanner.BOUNDARY_WALKABLE_NEIGHBOR_THRESHOLD's identical
    // reasoning, but this constant is kept separate (4, not RegionScanner's 6) and PROVISIONAL:
    // this class's own frontier trigger only decides whether to ALSO run a macro-project search on
    // top of a core flood that can now relax TUNNEL/BRIDGE/CARVED_STAIR/AIR_STAIR itself - a
    // straight wall (5 of 8 WALK) may or may not need to trip this the way RegionScanner's
    // boundary-cell bookkeeping does. Not settled by a unit test - Task 21's go/no-go gate (the 4
    // existing air-stair GameTests) is what falsifies this threshold if it's wrong; revisit here
    // first if any of those regress.
    private static final int FRONTIER_WALK_THRESHOLD = 4;

    // Minimum real time between liveDebugMap publishes. Was previously "every 50 nodes"
    // regardless of map size - an O(n^2) cost over a full pass (~2287 full-map copies for a
    // 114k-node pass, copying a map that itself grows to 114k entries) that scales directly with
    // total node count and became catastrophic once Config.maxFlowFieldNodes was raised well
    // past its default. This snapshot only feeds the debug visualizer/dump, which never needs
    // more than a few updates per second regardless of how large the map has grown.
    private static final long LIVE_DEBUG_PUBLISH_INTERVAL_MS = 250;

    private PriorityQueue<QueueNode> calcQueue;
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, FlowStep> nextInstructionMap = new HashMap<>();
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
    private volatile Map<BlockPos, FlowStep> liveDebugMap = Collections.emptyMap();

    // Positions dropped by breakMutualCycles() on the immediately preceding pass, so a still-
    // recurring producer bug doesn't spam a fresh WARN every single recompute (this class's own
    // recompute cadence is gated by TerritoryRegionMap's 80-tick/~4s cooldown, but that's still
    // often enough to flood the log for as long as the underlying bug persists). Replaced
    // wholesale each pass rather than merged/grown, so a bug whose shape changes (a different
    // position now affected) is still surfaced, while an unchanged repeat goes quiet.
    private Set<BlockPos> lastPassWarnedPositions = Collections.emptySet();

    private final PathStepEvaluator pathStepEvaluator;
    private final SiegeProjectManager projectManager;
    private final CalculationThrottler throttler;

    public FlowFieldCalculator(PathStepEvaluator pathStepEvaluator, SiegeProjectManager manager, CalculationThrottler throttler) {
        this.pathStepEvaluator = pathStepEvaluator;
        this.projectManager = manager;
        this.throttler = throttler;
    }

    /**
     * Returns an unmodifiable atomic snapshot of live calculation progress.
     */
    public Map<BlockPos, FlowStep> getLiveDebugMap() {
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
        liveDebugMap = Collections.emptyMap();
        lastLiveDebugPublishMs = System.currentTimeMillis();

        calcQueue = new PriorityQueue<>();
        BlockPos targetPos = state.getTargetPos();

        calcQueue.add(new QueueNode(targetPos, 0));
        nextCostMap.put(targetPos, 0);
        nextInstructionMap.put(targetPos, new FlowStep(targetPos, PathAction.WALK, targetPos));

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

            boolean hitObstacle = processNeighbors(terrain, current, currentCost, state);

            FlowStep currentInstruction = nextInstructionMap.get(current);
            // Replacement for the old isPlannedLanding check: BUILD_LANDING no longer exists as an
            // action, but the underlying need doesn't go away - this is the only path by which the
            // flood can start a NEW macro-project search from a cell that isn't walkable in the
            // real world YET (a planned chain's own terminal standable cell). Without it, a
            // construction chain could only ever be one project long, since isWalkableTerrain is
            // false there by definition and it usually isn't the target either - see
            // testLargeGroupBuildsChainedStaircaseAcrossGiantGap, one of Task 21's go/no-go gate
            // tests, which exercises exactly this. "current has an instruction a project wrote,
            // planning to make it standable" generalizes the old BUILD_LANDING-specific check to
            // any construction action - Task 11 owns refining this further if its own
            // isChainedPlatform work finds it needs to be narrower.
            boolean isPlannedConstructionTarget = currentInstruction != null && currentInstruction.action() != PathAction.WALK;

            if (hitObstacle && (pathStepEvaluator.isWalkableTerrain(terrain, current) || current.equals(state.getTargetPos()) || isPlannedConstructionTarget)) {
                projectManager.evaluateMacroProjects(terrain, current, state, currentCost, calcQueue, nextCostMap, nextInstructionMap);
            }
        }

        finalizeCalculation(state);
    }

    /** Relaxes every candidate step from {@code current} into the shared cost/instruction maps
     * (standard Dijkstra: only write through if strictly cheaper than what's already known), then
     * reports whether {@code current} counts as a frontier cell - see {@link #isFrontierCell}. */
    private boolean processNeighbors(TerrainAccess terrain, BlockPos current, int currentCost, FlowFieldState state) {
        List<PathStepEvaluator.EvaluatedStep> steps = pathStepEvaluator.candidateSteps(
                terrain, current, projectManager.getLockedPositions(),
                pos -> pathStepEvaluator.isOutOfBounds(terrain, pos, state));

        for (PathStepEvaluator.EvaluatedStep step : steps) {
            int totalCost = currentCost + step.cost();

            if (totalCost < nextCostMap.getOrDefault(step.pos(), Integer.MAX_VALUE)) {
                nextCostMap.put(step.pos(), totalCost);
                nextInstructionMap.put(step.pos(), new FlowStep(step.pos(), step.action(), current));
                calcQueue.add(new QueueNode(step.pos(), totalCost));
            }
        }

        return isFrontierCell(steps);
    }

    /** See {@link #FRONTIER_WALK_THRESHOLD}'s own doc for what this threshold means and why it's
     * provisional. Split out as a pure, dependency-free function so it's unit-testable without a
     * fully-constructed FlowFieldCalculator - see FlowFieldCalculatorTest. */
    static boolean isFrontierCell(List<PathStepEvaluator.EvaluatedStep> steps) {
        long walkCount = steps.stream().filter(step -> step.action() == PathAction.WALK).count();
        return walkCount < FRONTIER_WALK_THRESHOLD;
    }

    private void finalizeCalculation(FlowFieldState state) {
        breakMutualCycles();

        // Atomic snapshot update
        this.liveDebugMap = Map.copyOf(nextInstructionMap);

        lastPassNodeCount = nextCostMap.size();

        state.updateInstructions(new HashMap<>(nextInstructionMap));
        projectManager.finalizeCandidateProjects(nextCostMap, state.getInstructionMap());
    }

    // Every ordinary core-flood write (processNeighbors) is gated by
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
                current = nextInstructionMap.get(current).predecessorPos();
            } while (!current.equals(start));

            BlockPos toDrop = pickCyclePositionToDrop(cycle, nextCostMap, lockedPositions);
            FlowStep droppedNode = nextInstructionMap.get(toDrop);
            long lockedCount = cycle.stream().filter(lockedPositions::contains).count();
            boolean lockedPreference = lockedCount > 0 && lockedCount < cycle.size();

            warnedThisPass.add(toDrop);
            if (!lastPassWarnedPositions.contains(toDrop)) {
                LOGGER.warn("[Pathfinder] Broke flow-field cycle of {} position(s): dropped {} ({}, locked={}) "
                                + "which pointed at {}, kept the other {} position(s) - reason: {} - "
                                + "dropped so mobs fall back to local breach instead of looping forever",
                        cycle.size(), toDrop.toShortString(), droppedNode.action(), lockedPositions.contains(toDrop),
                        droppedNode.predecessorPos().toShortString(), cycle.size() - 1,
                        lockedPreference
                                ? "kept every locked position in this cycle (" + lockedCount + " of " + cycle.size()
                                        + "), dropped one of the non-locked ones"
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
     *     <li>if AT LEAST ONE of the cycle's positions is locked (an active SiegeProject's own
     *     claimed cell - see {@code SiegeProjectManager.getLockedPositions}) but NOT EVERY
     *     position is locked, restrict the candidates to the non-locked ones and keep every locked
     *     position: the core flood skips locked positions entirely, so a locked cell dropped here
     *     could never be refilled by a later pass. This isn't limited to exactly one locked
     *     position - {@code SiegeProjectManager} can hold several concurrent {@code
     *     activeProjects} at once, all contributing to the same shared {@code lockedPositions}
     *     set, and a single any-length cycle (see {@link #detectMutualCyclePositions}) can span
     *     cells locked by more than one of them; every one of those locked cells must survive, not
     *     just "the" locked one.</li>
     *     <li>if EVERY position in the cycle is locked, there's no unlocked alternative to prefer,
     *     so fall through to the cost/tiebreak logic below over the full cycle regardless of lock
     *     state - the cycle still has to be broken somehow.</li>
     *     <li>otherwise (including the "every position locked" fallback above), keep whichever
     *     position is cheaper in {@code costMap} and drop the higher-cost one. A macro-project
     *     chain's interior positions legitimately have no {@code costMap} entry at all (see
     *     {@code nextCostMap}'s own field doc) - an absent cost is treated the same as a tie. On a
     *     tie (or an absent cost on either side), drop whichever position was encountered LATER
     *     while walking the cycle, keeping the first one encountered - an arbitrary but
     *     deterministic tie-break.</li>
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
        if (lockedCount > 0 && lockedCount < cyclePositions.size()) {
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
     * reachable by repeatedly following each position's own {@code .predecessorPos()} - within
     * this one map. Only this region's own {@code nextInstructionMap} is ever visible here: a
     * cycle spanning two different regions' own separately-computed instruction maps is out of
     * scope and NOT detected (see {@link #breakMutualCycles()}'s doc). Split out from
     * {@link #breakMutualCycles()} as a pure, dependency-free function (no logging, no
     * locked-position lookup, no instance state) so it's unit-testable against a plain fixture map
     * instead of requiring a fully-constructed FlowFieldCalculator - see FlowFieldCalculatorTest.
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
    static Set<BlockPos> detectMutualCyclePositions(Map<BlockPos, FlowStep> instructionMap) {
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

                FlowStep node = instructionMap.get(current);
                if (node == null) break; // dead end - no instruction here at all

                BlockPos next = node.predecessorPos();
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
