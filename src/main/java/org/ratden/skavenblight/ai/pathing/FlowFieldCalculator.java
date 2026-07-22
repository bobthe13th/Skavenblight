package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;

import java.util.*;

public class FlowFieldCalculator {

    private PriorityQueue<QueueNode> calcQueue;
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

    private final TerrainEvaluator terrainEvaluator;
    private final SiegeProjectManager projectManager;

    public FlowFieldCalculator(TerrainEvaluator evaluator, SiegeProjectManager manager) {
        this.terrainEvaluator = evaluator;
        this.projectManager = manager;
    }

    // =================================================================================
    // PUBLIC API
    // =================================================================================

    /**
     * Executes the entire FlowField calculation.
     * THIS MUST BE CALLED FROM A BACKGROUND THREAD.
     */
    public void calculateFully(ServerLevel level, FlowFieldState state) {
        startCalculation(level, state);
        processCalculationQueue(level, state);
    }

    // =================================================================================
    // ALGORITHM CORE
    // =================================================================================

    private void startCalculation(ServerLevel level, FlowFieldState state) {
        nextCostMap.clear();
        nextInstructionMap.clear();

        calcQueue = new PriorityQueue<>();
        BlockPos targetPos = state.getTargetPos();

        calcQueue.add(new QueueNode(targetPos, 0));
        nextCostMap.put(targetPos, 0);
        nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));

        projectManager.injectActiveProjects(level, calcQueue, nextCostMap, nextInstructionMap);
    }

    private void processCalculationQueue(ServerLevel level, FlowFieldState state) {
        BlockPos targetPos = state.getTargetPos();

        // Loop runs continuously until the queue is empty or the max node limit is hit
        while (!calcQueue.isEmpty()) {
            if (nextCostMap.size() >= Config.maxFlowFieldNodes) {
                calcQueue.clear();
                break;
            }

            QueueNode qNode = calcQueue.poll();
            BlockPos current = qNode.pos();
            int currentCost = qNode.cost();

            if (currentCost > nextCostMap.getOrDefault(current, Integer.MAX_VALUE)) continue;

            boolean hitObstacle = processOrthogonalNeighbors(level, current, currentCost, targetPos);

            if (hitObstacle && (terrainEvaluator.isWalkableTerrain(level, current) || current.equals(targetPos))) {
                projectManager.evaluateMacroProjects(level, current, targetPos, currentCost, calcQueue, nextCostMap, nextInstructionMap);
            }
        }

        finalizeCalculation(state);
    }

    private boolean processOrthogonalNeighbors(ServerLevel level, BlockPos current, int currentCost, BlockPos targetPos) {
        List<TerrainEvaluator.EvaluatedStep> validSteps = terrainEvaluator.getValidOrthogonalSteps(level, current, projectManager.getLockedPositions(), targetPos);

        for (TerrainEvaluator.EvaluatedStep step : validSteps) {
            int totalCost = currentCost + step.cost();

            if (totalCost < nextCostMap.getOrDefault(step.pos(), Integer.MAX_VALUE)) {
                nextCostMap.put(step.pos(), totalCost);
                nextInstructionMap.put(step.pos(), new SiegeNode(current, step.action()));
                calcQueue.add(new QueueNode(step.pos(), totalCost));
            }
        }

        return validSteps.size() < 8;
    }

    private void finalizeCalculation(FlowFieldState state) {
        // Pass maps to State and Foreman
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