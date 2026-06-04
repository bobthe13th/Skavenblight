package org.ratden.skavenblight;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModEvents {

    @SubscribeEvent
    public static void onAttributeCreate(EntityAttributeCreationEvent event) {
        event.put(ModEntities.RAT_WOLF.get(), RatWolf.createAttributes().build());
    }
}