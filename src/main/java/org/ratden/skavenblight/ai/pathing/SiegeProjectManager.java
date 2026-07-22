package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;

import java.util.*;

/**
 * The Foreman. Manages the lifecycle of active, planned, and candidate SiegeProjects.
 * Evaluates macro-lines during pathfinding to generate construction and mining blueprints.
 */
public class SiegeProjectManager {

    private static final int MAX_PROJECT_LENGTH = 32;
    private static final int PROJECT_SPACING_SQR = 256;
    private static final int COST_MULTIPLIER = 10;
    private static final int[][] CARDINAL_OFFSETS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    private final List<SiegeProject> activeProjects = new ArrayList<>();
    private final List<SiegeProject> candidateProjects = new ArrayList<>();
    private final List<BlockPos> plannedProjects = new ArrayList<>();
    private final Set<BlockPos> lockedPositions = new HashSet<>();

    private final TerrainEvaluator terrainEvaluator;

    public SiegeProjectManager(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
    }

    // =================================================================================
    // LIFECYCLE MANAGEMENT
    // =================================================================================

    public void injectActiveProjects(ServerLevel level,
                                     PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                     Map<BlockPos, Integer> nextCostMap,
                                     Map<BlockPos, SiegeNode> nextInstructionMap) {
        plannedProjects.clear();
        lockedPositions.clear();
        candidateProjects.clear();

        activeProjects.removeIf(project -> project.isCompleted(level, terrainEvaluator));

        for (SiegeProject project : activeProjects) {
            for (Map.Entry<BlockPos, SiegeNode> entry : project.getRemainingInstructions(level, terrainEvaluator).entrySet()) {
                BlockPos pos = entry.getKey();
                int penaltyCost = COST_MULTIPLIER * 10;

                lockedPositions.add(pos);
                nextInstructionMap.put(pos, entry.getValue());
                nextCostMap.put(pos, penaltyCost);
                calcQueue.add(new FlowFieldCalculator.QueueNode(pos, penaltyCost));
            }
        }
    }

    public void finalizeCandidateProjects(Map<BlockPos, Integer> finalCostMap, Map<BlockPos, SiegeNode> finalInstructionMap) {
        for (SiegeProject project : candidateProjects) {
            // Pass the cost map so the project can validate its entry point
            if (project.survivedMapOverwrite(finalCostMap, finalInstructionMap)) {
                this.activeProjects.add(project);
            }
        }
        candidateProjects.clear();
    }

    public Set<BlockPos> getLockedPositions() {
        return Collections.unmodifiableSet(this.lockedPositions);
    }

    // =================================================================================
    // MACRO PROJECT EVALUATION
    // =================================================================================

    public void evaluateMacroProjects(ServerLevel level, BlockPos anchorPos, BlockPos targetPos, int anchorCost,
                                      PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                      Map<BlockPos, Integer> nextCostMap,
                                      Map<BlockPos, SiegeNode> nextInstructionMap) {
        for (int[] dir : CARDINAL_OFFSETS) {
            for (int dy = -1; dy <= 1; dy++) {
                // Now correctly passing 10 arguments
                evaluateSingleLine(level, anchorPos, targetPos, anchorCost, dir[0], dy, dir[1], calcQueue, nextCostMap, nextInstructionMap);
            }
        }
    }

    // Added 'BlockPos targetPos' to this signature!
    private void evaluateSingleLine(ServerLevel level, BlockPos anchorPos, BlockPos targetPos, int anchorCost,
                                    int dx, int dy, int dz,
                                    PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                    Map<BlockPos, Integer> nextCostMap,
                                    Map<BlockPos, SiegeNode> nextInstructionMap) {

        int projectCost = Config.buildingBasePenalty * COST_MULTIPLIER;
        int mineProjectLength = 0; // Track consecutive mine actions for the Mountain Check
        BlockPos currentTarget = anchorPos;

        Map<BlockPos, Integer> tempCostMap = new HashMap<>();
        Map<BlockPos, SiegeNode> tempInstructionMap = new HashMap<>();
        Map<BlockPos, SiegeNode> projectInstructions = new HashMap<>();

        for (int i = 1; i <= MAX_PROJECT_LENGTH; i++) {
            BlockPos ratPos = currentTarget.offset(-dx, -dy, -dz);

            // targetPos is now resolved and passed correctly
            if (terrainEvaluator.isOutOfBounds(level, ratPos, targetPos) || lockedPositions.contains(ratPos)) break;
            if (isNearExistingProject(ratPos)) return;

            SiegeNode actionNode = terrainEvaluator.determineMacroAction(level, currentTarget, dy, dx, dz);
            if (actionNode == null) return;

            // The Mountain Check: Prevent mining through excessively thick walls
            if (actionNode.action() == SiegeNode.SiegeAction.MINE) {
                mineProjectLength++;
                if (mineProjectLength > 5) return; // Abort! Wall is too thick.
            } else {
                mineProjectLength = 0; // Reset if we are building/walking again
            }

            projectCost += terrainEvaluator.calculateActionCost(level, actionNode);

            int evaluatedProjectCost = (dy != 0) ? (int) ((projectCost * 2) * 0.75f) : (projectCost * 2);
            int totalCost = anchorCost + evaluatedProjectCost;

            if (totalCost >= nextCostMap.getOrDefault(ratPos, Integer.MAX_VALUE)) return;

            tempCostMap.put(ratPos, totalCost);
            tempInstructionMap.put(ratPos, actionNode);
            tempCostMap.put(ratPos.below(), totalCost);
            tempInstructionMap.put(ratPos.below(), actionNode);

            if (actionNode.action() != SiegeNode.SiegeAction.WALK) {
                if (!terrainEvaluator.isWalkableTerrain(level, ratPos)) projectInstructions.put(ratPos, actionNode);
                if (!terrainEvaluator.isWalkableTerrain(level, ratPos.below())) projectInstructions.put(ratPos.below(), actionNode);
            }

            if (terrainEvaluator.isWalkableTerrain(level, ratPos)) {
                nextCostMap.putAll(tempCostMap);
                nextInstructionMap.putAll(tempInstructionMap);

                if (!projectInstructions.isEmpty()) {
                    // THE FIX: Pass the entry position (ratPos) and the total penalty cost!
                    candidateProjects.add(new SiegeProject(projectInstructions, ratPos, totalCost));
                }

                calcQueue.add(new FlowFieldCalculator.QueueNode(ratPos, totalCost));
                plannedProjects.add(anchorPos);
                return;
            }

            currentTarget = ratPos;
        }
    }

    private boolean isNearExistingProject(BlockPos pos) {
        return plannedProjects.stream().anyMatch(existing -> existing.distSqr(pos) < PROJECT_SPACING_SQR);
    }
}