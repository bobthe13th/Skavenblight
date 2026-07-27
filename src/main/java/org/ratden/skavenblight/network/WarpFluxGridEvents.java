package org.ratden.skavenblight.network;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.ratden.skavenblight.Skavenblight;

/**
 * Handles runtime events belonging to the Warp Flux grid system.
 */
@EventBusSubscriber(modid = Skavenblight.MODID)
public final class WarpFluxGridEvents {

    @SubscribeEvent
    public static void onLevelTick(
            LevelTickEvent.Post event
    ) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        WarpFluxGridManager manager =
                WarpFluxGridManager.get(serverLevel);

        manager.tickNetworks(serverLevel);
    }

    private WarpFluxGridEvents() {
    }
}