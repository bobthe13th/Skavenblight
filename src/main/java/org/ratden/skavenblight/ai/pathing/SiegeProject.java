package org.ratden.skavenblight.ai.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.HashMap;
import java.util.Map;

/**
 * A data class representing an active multi-block building or mining project.
 */
public class SiegeProject {

    private final Map<BlockPos, SiegeNode> instructions;

    public SiegeProject(Map<BlockPos, SiegeNode> instructions) {
        this.instructions = new HashMap<>(instructions);
    }

    public boolean isCompleted(ServerLevel level, TerrainEvaluator evaluator) {
        return instructions.values().stream().allMatch(node -> evaluator.isActionCompleted(level, node));
    }

    public boolean survivedMapOverwrite(Map<BlockPos, SiegeNode> finalMap) {
        return instructions.entrySet().stream().allMatch(entry -> {
            SiegeNode finalNode = finalMap.get(entry.getKey());
            return finalNode != null && finalNode.action() == entry.getValue().action();
        });
    }

    public Map<BlockPos, SiegeNode> getRemainingInstructions(ServerLevel level, TerrainEvaluator evaluator) {
        Map<BlockPos, SiegeNode> remaining = new HashMap<>();
        instructions.forEach((pos, node) -> {
            if (!evaluator.isActionCompleted(level, node)) {
                remaining.put(pos, node);
            }
        });
        return remaining;
    }
}