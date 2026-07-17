package org.ratden.skavenblight.debug.mode.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import java.util.Map;

public class WildernessServerMode implements IServerDebugMode {
    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, StandardFlowField sharedField, Map<BlockPos, SiegeNode> localNodes) {
        // Evaluate a 5x5 grid around the player to see dynamic choices nearby
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -1; y <= 2; y++) {
                    BlockPos evalPos = playerPos.offset(x, y, z);
                    SiegeNode wildNode = sharedField.getDynamicWildernessNode(level, evalPos);
                    if (wildNode != null) {
                        localNodes.put(evalPos, wildNode);
                    }
                }
            }
        }
    }
}