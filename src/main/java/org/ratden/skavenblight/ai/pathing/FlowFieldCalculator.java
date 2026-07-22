package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;

import java.util.*;

/**
 * The core Dijkstra time-sliced math engine.
 * Responsible for mapping distances and directions from the target outward.
 */
public class FlowFieldCalculator {

    private PriorityQueue<QueueNode> calcQueue;
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

    private final TerrainEvaluator terrainEvaluator;
    private final SiegeProjectManager projectManager;

    private boolean isCalculating = false;

    public FlowFieldCalculator(TerrainEvaluator evaluator, SiegeProjectManager manager) {
        this.terrainEvaluator = evaluator;
        this.projectManager = manager;
    }

    // =================================================================================
    // PUBLIC API
    // =================================================================================

    public void calculateMapIfNeeded(ServerLevel level, FlowFieldState state, int nodesPerTick) {
        if (!isCalculating) {
            startCalculation(level, state);
        }

        if (isCalculating) {
            processCalculationQueue(level, state, nodesPerTick);
        }
    }

    public boolean isCalculating() {
        return isCalculating;
    }

    // =================================================================================
    // ALGORITHM CORE
    // =================================================================================

    private void startCalculation(ServerLevel level, FlowFieldState state) {
        isCalculating = true;
        nextCostMap.clear();
        nextInstructionMap.clear();

        calcQueue = new PriorityQueue<>();
        BlockPos targetPos = state.getTargetPos();

        calcQueue.add(new QueueNode(targetPos, 0));
        nextCostMap.put(targetPos, 0);
        nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));

        // Delegate re-injecting active projects to the manager
        projectManager.injectActiveProjects(level, calcQueue, nextCostMap, nextInstructionMap);
    }

    private void processCalculationQueue(ServerLevel level, FlowFieldState state, int nodesPerTick) {
        int nodesProcessed = 0;
        BlockPos targetPos = state.getTargetPos();

        while (!calcQueue.isEmpty() && nodesProcessed < nodesPerTick) {
            if (nextCostMap.size() >= Config.maxFlowFieldNodes) {
                calcQueue.clear();
                break;
            }

            QueueNode qNode = calcQueue.poll();
            BlockPos current = qNode.pos();
            int currentCost = qNode.cost();
            nodesProcessed++;

            // Skip stale nodes in the priority queue
            if (currentCost > nextCostMap.getOrDefault(current, Integer.MAX_VALUE)) continue;

            // Thread the targetPos into the orthogonal evaluation
            processOrthogonalNeighbors(level, current, currentCost, targetPos);

            // Ask the TerrainEvaluator if this block is a valid anchor for a macro project
            if (terrainEvaluator.isWalkableTerrain(level, current) || current.equals(targetPos)) {
                // Thread the targetPos into the project manager
                projectManager.evaluateMacroProjects(level, current, targetPos, currentCost, calcQueue, nextCostMap, nextInstructionMap);
            }
        }

        if (calcQueue.isEmpty()) {
            finalizeCalculation(state);
        }
    }

    private void processOrthogonalNeighbors(ServerLevel level, BlockPos current, int currentCost, BlockPos targetPos) {
        // Delegate voxel evaluation completely to the TerrainEvaluator, now passing targetPos
        List<TerrainEvaluator.EvaluatedStep> validSteps = terrainEvaluator.getValidOrthogonalSteps(level, current, projectManager.getLockedPositions(), targetPos);

        for (TerrainEvaluator.EvaluatedStep step : validSteps) {
            int totalCost = currentCost + step.cost();

            if (totalCost < nextCostMap.getOrDefault(step.pos(), Integer.MAX_VALUE)) {
                nextCostMap.put(step.pos(), totalCost);
                nextInstructionMap.put(step.pos(), new SiegeNode(current, step.action()));
                calcQueue.add(new QueueNode(step.pos(), totalCost));
            }
        }
    }

    private void finalizeCalculation(FlowFieldState state) {
        isCalculating = false;

        // Hand the finished maps over to the state object
        state.updateInstructions(new HashMap<>(nextInstructionMap));

        // Tell the manager to filter candidate projects against the new map
        projectManager.finalizeCandidateProjects(state.getInstructionMap());
    }

    // =================================================================================
    // INNER DATA
    // =================================================================================

    public record QueueNode(BlockPos pos, int cost) implements Comparable<QueueNode> {
        @Override
        public int compareTo(QueueNode o) {
            return Integer.compare(this.cost, o.cost);
        }
    }
}