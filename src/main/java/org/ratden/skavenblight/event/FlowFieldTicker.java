package org.ratden.skavenblight.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;

@EventBusSubscriber(modid = "skavenblight")
public class FlowFieldTicker {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        // Ensure this only runs on the server side
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
            if (gridManager == null) return;

            // Iterate through active networks and tick their flow fields
            for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
                for (BlockPos endpoint : network.getEndpoints()) {
                    if (serverLevel.getBlockEntity(endpoint) instanceof WarpstoneNexusEntity) {
                        StandardFlowField sharedField = network.getSharedFlowField(endpoint);
                        if (sharedField != null) {
                            // This ensures the queue processes continuously in the background!
                            sharedField.calculateMapIfNeeded(serverLevel);
                        }
                    }
                }
            }
        }
    }
}