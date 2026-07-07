package org.ratden.skavenblight.entity;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.custom.wolfCat.WolfCat;

@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD
)
public class ModEntityAttributes {

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {

        event.put(
                ModEntities.WOLF_CAT.get(),
                WolfCat.createAttributes().build()
        );

    }

    private ModEntityAttributes() {
    }
}