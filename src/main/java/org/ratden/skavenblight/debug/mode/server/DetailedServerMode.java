package org.ratden.skavenblight.debug.mode.server;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.debug.mode.server.IServerDebugMode;

import java.util.Map;

public class DetailedServerMode implements IServerDebugMode {
    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, StandardFlowField sharedField, Map<BlockPos, SiegeNode> localNodes) {
        for (BlockPos pos : sharedField.getInstructionMap().keySet()) {
            if (pos.closerThan(playerPos, 16)) {
                SiegeNode nextNode = sharedField.getNextSiegeNode(level, pos);
                if (nextNode != null) localNodes.put(pos, nextNode);
            }
        }
    }
}