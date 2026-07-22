package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FlowFieldCalculator {

    private PriorityQueue<QueueNode> calcQueue;
    private final Map<BlockPos, Integer> nextCostMap = new HashMap<>();
    private final Map<BlockPos, SiegeNode> nextInstructionMap = new HashMap<>();

    // Thread-safe map exposed to debug visualizers while calculations run off-thread
    private final Map<BlockPos, SiegeNode> liveDebugMap = new ConcurrentHashMap<>();

    private final TerrainEvaluator terrainEvaluator;
    private final SiegeProjectManager projectManager;

    public FlowFieldCalculator(TerrainEvaluator evaluator, SiegeProjectManager manager) {
        this.terrainEvaluator = evaluator;
        this.projectManager = manager;
    }

    /**
     * Returns an unmodifiable snapshot of the live calculation progress.
     */
    public Map<BlockPos, SiegeNode> getLiveDebugMap() {
        return Collections.unmodifiableMap(this.liveDebugMap);
    }

    public void calculateFully(ServerLevel level, FlowFieldState state) {
        startCalculation(level, state);
        processCalculationQueue(level, state);
    }

    private void startCalculation(ServerLevel level, FlowFieldState state) {
        nextCostMap.clear();
        nextInstructionMap.clear();
        liveDebugMap.clear();

        calcQueue = new PriorityQueue<>();
        BlockPos targetPos = state.getTargetPos();

        calcQueue.add(new QueueNode(targetPos, 0));
        nextCostMap.put(targetPos, 0);
        nextInstructionMap.put(targetPos, new SiegeNode(targetPos, SiegeNode.SiegeAction.WALK));

        projectManager.injectActiveProjects(level, calcQueue, nextCostMap, nextInstructionMap);
    }

    private void processCalculationQueue(ServerLevel level, FlowFieldState state) {
        while (!calcQueue.isEmpty()) {
            if (nextCostMap.size() >= Config.maxFlowFieldNodes) {
                calcQueue.clear();
                break;
            }

            // Periodically publish calculation progress for live visual rendering
            if (nextCostMap.size() % 50 == 0) {
                this.liveDebugMap.clear();
                this.liveDebugMap.putAll(nextInstructionMap);
            }

            QueueNode qNode = calcQueue.poll();
            BlockPos current = qNode.pos();
            int currentCost = qNode.cost();

            if (currentCost > nextCostMap.getOrDefault(current, Integer.MAX_VALUE)) continue;

            // FlowFieldState acts as the boundary enforcer
            boolean hitObstacle = processOrthogonalNeighbors(level, current, currentCost, state);

            // Identify if this node is a planned midair landing blueprint
            SiegeNode currentInstruction = nextInstructionMap.get(current);
            boolean isPlannedLanding = currentInstruction != null && currentInstruction.action() == SiegeNode.SiegeAction.BUILD_LANDING;

            // Allow macro evaluation if the node is physically walkable, the target, OR a planned landing
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
        // Final sync to guarantee debug visualizer matches finalized map state
        this.liveDebugMap.clear();
        this.liveDebugMap.putAll(nextInstructionMap);

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