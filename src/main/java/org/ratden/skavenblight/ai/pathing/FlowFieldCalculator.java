package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;

import java.util.*;

public class FlowFieldCalculator {

    private PriorityQueue<QueueNode> calcQueue;
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

    // FIXED: Volatile immutable map reference for atomic, zero-flicker snapshot reads across threads
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

    public void calculateFully(ServerLevel level, FlowFieldState state) {
        startCalculation(level, state);
        processCalculationQueue(level, state);
    }

    private void startCalculation(ServerLevel level, FlowFieldState state) {
        nextCostMap.clear();
        nextInstructionMap.clear();
        liveDebugMap = Collections.emptyMap();

        calcQueue = new PriorityQueue<>();
        BlockPos targetPos = state.getTargetPos();

        calcQueue.add(new QueueNode(targetPos, 0));
        nextCostMap.put(targetPos, 0);
        nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));

        projectManager.injectActiveProjects(level, calcQueue, nextCostMap, nextInstructionMap);
    }

    private void processCalculationQueue(ServerLevel level, FlowFieldState state) {
        // FIXED: Dynamically throttle max allowed nodes per calculation using MSPT metric
        int maxAllowedNodes = Math.min(Config.maxFlowFieldNodes, throttler.getNodesPerTick());

        while (!calcQueue.isEmpty()) {
            if (nextCostMap.size() >= maxAllowedNodes) {
                calcQueue.clear();
                break;
            }

            // FIXED: Atomically publish progress snapshot without clearing the active map
            if (nextCostMap.size() % 50 == 0) {
                this.liveDebugMap = Map.copyOf(nextInstructionMap);
            }

            QueueNode qNode = calcQueue.poll();
            BlockPos current = qNode.pos();
            int currentCost = qNode.cost();

            if (currentCost > nextCostMap.getOrDefault(current, Integer.MAX_VALUE)) continue;

            boolean hitObstacle = processOrthogonalNeighbors(level, current, currentCost, state);

            SiegeNode currentInstruction = nextInstructionMap.get(current);
            boolean isPlannedLanding = currentInstruction != null && currentInstruction.action() == SiegeNode.SiegeAction.BUILD_LANDING;

            if (hitObstacle && (terrainEvaluator.isWalkableTerrain(level, current) || current.equals(state.getTargetPos()) || isPlannedLanding)) {
                projectManager.evaluateMacroProjects(level, current, state, currentCost, calcQueue, nextCostMap, nextInstructionMap);
            }
        }

        finalizeCalculation(state);
    }

    private boolean processOrthogonalNeighbors(ServerLevel level, BlockPos current, int currentCost, FlowFieldState state) {
        List<TerrainEvaluator.EvaluatedStep> validSteps = terrainEvaluator.getValidOrthogonalSteps(level, current, projectManager.getLockedPositions(), state);

        for (TerrainEvaluator.EvaluatedStep step : validSteps) {
            int totalCost = currentCost + step.cost();

            if (totalCost < nextCostMap.getOrDefault(step.pos(), Integer.MAX_VALUE)) {
                nextCostMap.put(step.pos(), totalCost);
                nextInstructionMap.put(step.pos(), new SiegeNode(current, step.action()));
                calcQueue.add(new QueueNode(step.pos(), totalCost));
            }
        }

        long walkableNeighbors = validSteps.stream()
                .filter(s -> s.action() == SiegeNode.SiegeAction.WALK && s.pos().getY() == current.getY())
                .count();

        return walkableNeighbors < 4;
    }

    private void finalizeCalculation(FlowFieldState state) {
        // FIXED: Atomic snapshot update
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