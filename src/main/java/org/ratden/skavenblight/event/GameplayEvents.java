package org.ratden.skavenblight.event;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.event.skavenIncursion.SkavenIncursionHandler;

@EventBusSubscriber(modid = Skavenblight.MODID)
public class GameplayEvents {

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SkavenIncursionHandler.onServerTick(event);
    }
}