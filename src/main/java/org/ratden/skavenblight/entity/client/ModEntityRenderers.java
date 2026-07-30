package org.ratden.skavenblight.entity.client;

import net.minecraft.client.renderer.entity.CatRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.ModEntities;

/**
 * Registers client renderers for Skavenblight EntityTypes.
 *
 * This class is restricted to the physical client because entity renderer
 * classes depend on Minecraft client code.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class ModEntityRenderers {

    @SubscribeEvent
    public static void registerEntityRenderers(
            EntityRenderersEvent.RegisterRenderers event
    ) {
        event.registerEntityRenderer(
                ModEntities.RAT_WOLF.get(),
                RatWolfRenderer::new
        );

        event.registerEntityRenderer(
                ModEntities.WOLF_CAT.get(),
                CatRenderer::new
        );

        event.registerEntityRenderer(
                ModEntities.CLANRAT.get(),
                ClanratRenderer::new
        );

        event.registerEntityRenderer(
                ModEntities.WARP_LIGHTNING_BOLT.get(),
                WarpLightningBoltRenderer::new
        );
    }

    private ModEntityRenderers() {
    }
}