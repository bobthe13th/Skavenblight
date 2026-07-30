package org.ratden.skavenblight.event.skavenIncursion.debug;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.Skavenblight;

/**
 * Common event hooks for the source debug-visualisation system.
 *
 * Rendering logic remains in DebugSourceVisualisation. This class only
 * receives events and delegates them to the owning debug systems.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID
)
public final class DebugSourceVisualisationEvents {

    @SubscribeEvent
    public static void onServerTick(
            ServerTickEvent.Post event
    ) {
        DebugSourceVisualisation.tick(
                event.getServer()
        );
    }

    /**
     * Clears static debug-only state when a server lifecycle finishes.
     *
     * Physical debug-anchor blocks remain saved in the world. Only the
     * in-memory tracker and visualisation state are forgotten here.
     */
    @SubscribeEvent
    public static void onServerStopped(
            ServerStoppedEvent event
    ) {
        DebugSourceVisualisation.disable();
        DebugIncursionAnchorTracker.forgetAll();
    }

    private DebugSourceVisualisationEvents() {
    }
}