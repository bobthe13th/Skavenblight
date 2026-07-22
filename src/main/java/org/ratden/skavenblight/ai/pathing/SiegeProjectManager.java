package org.ratden.skavenblight.ai.pathing;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.Config;
import org.slf4j.Logger;

import java.util.*;

public class SiegeProjectManager {

    // --- Added Logger Field Declaration ---
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_PROJECT_LENGTH = 32;
    private static final int COST_MULTIPLIER = 10;
    private static final int[][] CARDINAL_OFFSETS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    private final List<SiegeProject> activeProjects = new ArrayList<>();
    private final List<SiegeProject> candidateProjects = new ArrayList<>();
    private final Set<BlockPos> plannedProjects = new HashSet<>();
    private final Set<BlockPos> lockedPositions = new HashSet<>();

    private final TerrainEvaluator terrainEvaluator;

    public SiegeProjectManager(TerrainEvaluator terrainEvaluator) {
        this.terrainEvaluator = terrainEvaluator;
    }

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
                lockedPositions.add(pos);
                nextInstructionMap.put(pos, entry.getValue());
            }

            BlockPos entry = project.getEntryPos();
            int entryCost = project.getExpectedEntryCost();

            if (entryCost < nextCostMap.getOrDefault(entry, Integer.MAX_VALUE)) {
                nextCostMap.put(entry, entryCost);
                calcQueue.add(new FlowFieldCalculator.QueueNode(entry, entryCost));
            }
        }
    }

    public void finalizeCandidateProjects(Map<BlockPos, Integer> finalCostMap, Map<BlockPos, SiegeNode> finalInstructionMap) {
        for (SiegeProject project : candidateProjects) {
            if (project.survivedMapOverwrite(finalCostMap, finalInstructionMap)) {
                this.activeProjects.add(project);
            }
        }
        candidateProjects.clear();
    }

    public Set<BlockPos> getLockedPositions() {
        return Collections.unmodifiableSet(this.lockedPositions);
    }

    public void evaluateMacroProjects(ServerLevel level, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                      PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                      Map<BlockPos, Integer> nextCostMap,
                                      Map<BlockPos, SiegeNode> nextInstructionMap) {

        // Self-Evaluation: Ensure anchor itself is valid
        if (!terrainEvaluator.isWalkableTerrain(level, anchorPos)) {
            SiegeNode.SiegeAction selfAction = terrainEvaluator.determineMacroAction(level, anchorPos, 0, 0, 0, state.getTargetPos());
            if (selfAction != SiegeNode.SiegeAction.WALK) {
                nextInstructionMap.putIfAbsent(anchorPos, new SiegeNode(anchorPos, selfAction));
            }
        }

        // 1. Evaluate vertical shafts & spiral columns around conduits
        for (int dy : new int[]{-1, 1}) {
            evaluateSingleLine(level, anchorPos, state, anchorCost, 0, dy, 0, calcQueue, nextCostMap, nextInstructionMap);
        }

        // 2. Evaluate horizontal bridges (dy=0) and diagonal staircases (dy=-1, 1)
        for (int[] dir : CARDINAL_OFFSETS) {
            for (int dy : new int[]{-1, 0, 1}) {
                evaluateSingleLine(level, anchorPos, state, anchorCost, dir[0], dy, dir[1], calcQueue, nextCostMap, nextInstructionMap);
            }
        }

        // Record anchorPos ONLY AFTER evaluating all directional rays from it
        plannedProjects.add(anchorPos.immutable());
    }

    private void evaluateSingleLine(ServerLevel level, BlockPos anchorPos, FlowFieldState state, int anchorCost,
                                    int dx, int dy, int dz,
                                    PriorityQueue<FlowFieldCalculator.QueueNode> calcQueue,
                                    Map<BlockPos, Integer> nextCostMap,
                                    Map<BlockPos, SiegeNode> nextInstructionMap) {

        int projectCost = Config.buildingBasePenalty * COST_MULTIPLIER;
        int mineProjectLength = 0;
        BlockPos currentTarget = anchorPos;

        Map<BlockPos, SiegeNode> projectInstructions = new HashMap<>();

        for (int i = 1; i <= MAX_PROJECT_LENGTH; i++) {
            BlockPos nextPos = currentTarget.offset(dx, dy, dz);

            if (terrainEvaluator.isOutOfBounds(level, nextPos, state)) {
                LOGGER.debug("[Pathfinder] Line aborted at {}: Out of bounds", nextPos.toShortString());
                break;
            }

            // Prevent project collisions while allowing tight zigzags/spirals
            if (isNearExistingProject(nextPos, anchorPos)) return;

            SiegeNode.SiegeAction action = terrainEvaluator.determineMacroAction(level, nextPos, dy, dx, dz, state.getTargetPos());
            if (action == null) {
                LOGGER.debug("[Pathfinder] Line aborted at {}: Invalid macro action", nextPos.toShortString());
                return;
            }

            if (action == SiegeNode.SiegeAction.MINE) {
                mineProjectLength++;
                if (mineProjectLength > 5) return;
            } else {
                mineProjectLength = 0;
            }

            projectCost += terrainEvaluator.calculateActionCostForAction(level, nextPos, action);

            int evaluatedProjectCost = (dy != 0) ? (int) ((projectCost * 2) * 0.75f) : (projectCost * 2);
            int totalCost = anchorCost + evaluatedProjectCost;

            if (totalCost >= nextCostMap.getOrDefault(nextPos, Integer.MAX_VALUE)) return;

            SiegeNode stepInstruction = new SiegeNode(currentTarget, action);
            projectInstructions.put(nextPos, stepInstruction);

            // 1. Natural Completion: Line reaches walkable ground
            if (terrainEvaluator.isWalkableTerrain(level, nextPos)) {
                LOGGER.info("[Pathfinder] Successful Macro Line built from {} to {} (Length: {}, Action: {})",
                        anchorPos.toShortString(), nextPos.toShortString(), i, action);
                if (!projectInstructions.isEmpty()) {
                    candidateProjects.add(new SiegeProject(projectInstructions, nextPos, totalCost));

                    if (totalCost < nextCostMap.getOrDefault(nextPos, Integer.MAX_VALUE)) {
                        nextCostMap.put(nextPos, totalCost);
                        nextInstructionMap.putAll(projectInstructions);
                        calcQueue.add(new FlowFieldCalculator.QueueNode(nextPos, totalCost));
                    }
                }
                return;
            }

            // 2. Chained Completion: Max length reached in midair -> Deploy Landing Platform Node
            if (i == MAX_PROJECT_LENGTH && !projectInstructions.isEmpty()) {
                SiegeNode landingNode = new SiegeNode(currentTarget, SiegeNode.SiegeAction.BUILD_LANDING);
                projectInstructions.put(nextPos, landingNode);

                candidateProjects.add(new SiegeProject(projectInstructions, nextPos, totalCost));

                if (totalCost < nextCostMap.getOrDefault(nextPos, Integer.MAX_VALUE)) {
                    nextCostMap.put(nextPos, totalCost);
                    nextInstructionMap.putAll(projectInstructions);
                    calcQueue.add(new FlowFieldCalculator.QueueNode(nextPos, totalCost));
                }
                return;
            }

            currentTarget = nextPos;
        }
    }

    private boolean isNearExistingProject(BlockPos pos, BlockPos currentAnchor) {
        for (BlockPos existing : plannedProjects) {
            if (existing.equals(currentAnchor)) continue;
            if (existing.distSqr(pos) < 9) {
                return true;
            }
        }
        return false;
    }
}