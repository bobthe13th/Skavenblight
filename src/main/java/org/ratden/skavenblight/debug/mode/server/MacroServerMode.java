package org.ratden.skavenblight.debug.mode.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.SiegeNode;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import java.util.Map;

public class MacroServerMode implements IServerDebugMode {
    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField, Map<BlockPos, SiegeNode> localNodes) {
        // Macro mode doesn't need to send block-level nodes; the client calculates the NavMesh from the mapped chunks.
        // (Superseded by the region-map-aware overload below, which is what the debug item
        // actually calls now - kept here only to satisfy the interface's abstract method.)
    }

    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField,
                             TerritoryRegionMap regionMap, Map<BlockPos, SiegeNode> localNodes) {
        localNodes.putAll(sharedField.getInstructionMap());

        Region region = regionMap.getRegionIndex().getRegions().stream()
                .filter(r -> r.getId() == sharedField.getRegionId()).findFirst().orElse(null);
        if (region != null) {
            for (BlockPos boundaryCell : region.getBoundaryCells()) {
                localNodes.putIfAbsent(boundaryCell, new SiegeNode(boundaryCell, SiegeNode.SiegeAction.WALK));
            }
        }
    }
}