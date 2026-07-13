package org.ratden.skavenblight.debug.mode.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import java.util.Map;

public interface IServerDebugMode {
    void collectData(ServerLevel level, BlockPos playerPos, StandardFlowField sharedField, Map<BlockPos, SiegeNode> localNodes);
}