package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.Map;

/**
 * A data class representing an active multi-block building or mining project.
 * This project may bridge directly to the ground, or it may be a chained segment
 * terminating in a mid-air BUILD_LANDING.
 */
public class SiegeProject {

    private final Map<BlockPos, SiegeNode> instructions;
    private final BlockPos entryPos; // The block where rats enter this project (can be on ground or a mid-air landing)
    private final int expectedEntryCost; // The massive penalty cost assigned to this project

    public SiegeProject(Map<BlockPos, SiegeNode> instructions, BlockPos entryPos, int expectedEntryCost) {
        this.instructions = new HashMap<>(instructions);
        this.entryPos = entryPos;
        this.expectedEntryCost = expectedEntryCost;
    }

    public boolean isCompleted(TerrainAccess terrain, TerrainEvaluator evaluator) {
        return instructions.values().stream().allMatch(node -> evaluator.isActionCompleted(terrain, node));
    }

    public boolean survivedMapOverwrite(Map<BlockPos, Integer> finalCostMap, Map<BlockPos, SiegeNode> finalInstructionMap) {
        // 1. ENTRY POINT VALIDATION
        // If the final Dijkstra map gave our entry point (whether ground or chained landing)
        // a cheaper cost than the project's massive penalty, a walkable highway exists! Kill the project.
        int finalCost = finalCostMap.getOrDefault(entryPos, Integer.MAX_VALUE);
        if (finalCost < expectedEntryCost) {
            return false;
        }

        // 2. Fallback check for the project blocks themselves
        return instructions.entrySet().stream().allMatch(entry -> {
            SiegeNode finalNode = finalInstructionMap.get(entry.getKey());
            return finalNode != null && finalNode.action() == entry.getValue().action();
        });
    }

    public Map<BlockPos, SiegeNode> getRemainingInstructions(TerrainAccess terrain, TerrainEvaluator evaluator) {
        Map<BlockPos, SiegeNode> remaining = new HashMap<>();
        instructions.forEach((pos, node) -> {
            if (!evaluator.isActionCompleted(terrain, node)) {
                remaining.put(pos, node);
            }
        });
        return remaining;
    }

    public BlockPos getEntryPos() {
        return entryPos;
    }

    public int getExpectedEntryCost() {
        return expectedEntryCost;
    }

}
