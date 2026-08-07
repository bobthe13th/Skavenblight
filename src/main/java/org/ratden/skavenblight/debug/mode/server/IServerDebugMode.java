package org.ratden.skavenblight.debug.mode.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import java.util.Map;

public interface IServerDebugMode {
    void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField, Map<BlockPos, FlowStep> localNodes);

    /**
     * Overload that also exposes the owning network's TerritoryRegionMap, for modes (e.g.
     * Macro) that need to overlay region/route-tree info a single RegionFlowField can't see on
     * its own (it's scoped to one region). Defaults to the single-region overload above so the
     * other modes aren't forced to implement this.
     */
    default void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField,
                              TerritoryRegionMap regionMap, Map<BlockPos, FlowStep> localNodes) {
        collectData(level, playerPos, sharedField, localNodes);
    }
}