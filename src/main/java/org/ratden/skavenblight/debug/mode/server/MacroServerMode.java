package org.ratden.skavenblight.debug.mode.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.region.Region;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import org.ratden.skavenblight.ai.pathing.region.TerritoryRegionMap;
import java.util.Map;

public class MacroServerMode implements IServerDebugMode {
    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField, Map<BlockPos, FlowStep> localNodes) {
        // Macro mode doesn't need to send block-level nodes; the client calculates the NavMesh from the mapped chunks.
        // (Superseded by the region-map-aware overload below, which is what the debug item
        // actually calls now - kept here only to satisfy the interface's abstract method.)
    }

    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField,
                             TerritoryRegionMap regionMap, Map<BlockPos, FlowStep> localNodes) {
        localNodes.putAll(sharedField.getInstructionMap());

        Region region = regionMap.getRegionGraph().getRegions().stream()
                .filter(r -> r.getId() == sharedField.getRegionId()).findFirst().orElse(null);
        if (region != null) {
            for (BlockPos boundaryCell : region.getBoundaryCells()) {
                // Self-referencing predecessorPos (see Task 25's decision): a synthetic
                // single-node debug step with no real predecessor.
                localNodes.putIfAbsent(boundaryCell, new FlowStep(boundaryCell, PathAction.WALK, boundaryCell));
            }
        }
    }
}