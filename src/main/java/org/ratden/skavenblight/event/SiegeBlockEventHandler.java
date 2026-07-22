package org.ratden.skavenblight.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.ai.pathing.StandardFlowField;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;
import org.ratden.skavenblight.network.WarpFluxGridManager;
import org.ratden.skavenblight.network.WarpFluxNetwork;

@EventBusSubscriber(modid = Skavenblight.MODID)
public class SiegeBlockEventHandler {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel serverLevel)) return;

        handleBlockChange(serverLevel, event.getPos());
        //System.out.println("[Skavenblight] Player broke block at: " + event.getPos());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel serverLevel)) return;

        handleBlockChange(serverLevel, event.getPos());
    }

    /**
     * Finds any active sieges on the server and notifies them that the terrain has changed.
     */
    private static void handleBlockChange(ServerLevel serverLevel, BlockPos pos) {
        WarpFluxGridManager gridManager = WarpFluxGridManager.get(serverLevel);
        if (gridManager == null) return;

        // Iterate through active networks to find active FlowFields, just like the FlowFieldTicker does
        for (WarpFluxNetwork network : gridManager.getAllNetworks()) {
            for (BlockPos endpoint : network.getEndpoints()) {
                if (serverLevel.getBlockEntity(endpoint) instanceof WarpstoneNexusEntity) {
                    StandardFlowField sharedField = network.getSharedFlowField(serverLevel, endpoint);
                    if (sharedField != null) {
                        sharedField.onBlockChanged(pos);
                    }
                }
            }
        }
    }
}