package org.ratden.skavenblight.entity;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.custom.ClanratEntity;
import org.ratden.skavenblight.entity.custom.WolfRat;
import org.ratden.skavenblight.entity.custom.wolfCat.WolfCat;

/**
 * Registers the base attribute set used by every custom living entity.
 *
 * The entity classes define their attribute values. This class connects
 * those attribute definitions to their registered EntityTypes.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD
)
public final class ModEntityAttributes {

    @SubscribeEvent
    public static void registerAttributes(
            EntityAttributeCreationEvent event
    ) {
        event.put(
                ModEntities.RAT_WOLF.get(),
                WolfRat.createAttributes().build()
        );

        event.put(
                ModEntities.WOLF_CAT.get(),
                WolfCat.createAttributes().build()
        );

        event.put(
                ModEntities.CLANRAT.get(),
                ClanratEntity.createAttributes().build()
        );
    }

    private ModEntityAttributes() {
    }
}