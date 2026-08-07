package org.ratden.skavenblight.debug.mode.server;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.debug.mode.server.IServerDebugMode;

import java.util.Map;

public class DetailedServerMode implements IServerDebugMode {
    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField, Map<BlockPos, FlowStep> localNodes) {
        Map<BlockPos, FlowStep> activeMap = sharedField.getInstructionMap();

        // If calculation is currently running, show the live progress map!
        if (activeMap.isEmpty() && sharedField.isCalculating()) {
            activeMap = sharedField.getLiveDebugMap();
        }

        for (Map.Entry<BlockPos, FlowStep> entry : activeMap.entrySet()) {
            if (entry.getKey().closerThan(playerPos, 24)) {
                localNodes.put(entry.getKey(), entry.getValue());
            }
        }
    }
}