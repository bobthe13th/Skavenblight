package org.ratden.skavenblight.debug.mode.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.ratden.skavenblight.ai.pathing.FlowStep;
import org.ratden.skavenblight.ai.pathing.PathAction;
import org.ratden.skavenblight.ai.pathing.region.RegionFlowField;
import java.util.Map;

public class WildernessServerMode implements IServerDebugMode {
    @Override
    public void collectData(ServerLevel level, BlockPos playerPos, RegionFlowField sharedField, Map<BlockPos, FlowStep> localNodes) {
        // Evaluate a 5x5 grid around the player to see dynamic choices nearby
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -1; y <= 2; y++) {
                    BlockPos evalPos = playerPos.offset(x, y, z);
                    // getDynamicWildernessNode never got ported to RegionFlowField (only
                    // getWildernessHeadingTarget did) - collapse the heading target down to a
                    // single WALK step toward it, same as the old per-tile debug arrow did.
                    BlockPos heading = sharedField.getWildernessHeadingTarget(evalPos);
                    // Self-referencing predecessorPos (see Task 25's decision): a synthetic
                    // single-node debug step with no real predecessor.
                    FlowStep wildNode = heading != null ? new FlowStep(heading, PathAction.WALK, heading) : null;
                    if (wildNode != null) {
                        localNodes.put(evalPos, wildNode);
                    }
                }
            }
        }
    }
}