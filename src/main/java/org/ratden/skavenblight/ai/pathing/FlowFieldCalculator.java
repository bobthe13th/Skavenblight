package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import org.ratden.skavenblight.Config;

import java.util.*;

public class FlowFieldCalculator {

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
     * Runs the full Dijkstra pass against {@code terrain}. In normal use this is a
     * TerrainSnapshot captured just before this method was kicked off on a background
     * thread - this method itself has no thread affinity, it's only as safe as whatever
     * TerrainAccess it's given. Never pass a LiveTerrainAccess here from a background
     * thread.
     */
    public void calculateFully(TerrainAccess terrain, FlowFieldState state) {
        startCalculation(terrain, state);
        processCalculationQueue(terrain, state);
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

    private void processCalculationQueue(TerrainAccess terrain, FlowFieldState state) {
        // Dynamically throttle max allowed nodes per calculation using MSPT metric
        int maxAllowedNodes = Math.min(Config.maxFlowFieldNodes, throttler.getNodesPerTick());

        while (!calcQueue.isEmpty()) {
            if (nextCostMap.size() >= maxAllowedNodes) {
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

        long walkableNeighbors = validSteps.stream()
                .filter(s -> s.action() == SiegeNode.SiegeAction.WALK && s.pos().getY() == current.getY())
                .count();

        return walkableNeighbors < 4;
    }

    private void finalizeCalculation(FlowFieldState state) {
        // Atomic snapshot update
        this.liveDebugMap = Map.copyOf(nextInstructionMap);

        state.updateInstructions(new HashMap<>(nextInstructionMap));
        projectManager.finalizeCandidateProjects(nextCostMap, state.getInstructionMap());
    }

    public record QueueNode(BlockPos pos, int cost) implements Comparable<QueueNode> {
        @Override
        public int compareTo(QueueNode o) {
            return Integer.compare(this.cost, o.cost);
        }
    }
}
