package org.ratden.skavenblight.event;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.client.RatWolfRenderer;

@EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModClientEvents {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.RAT_WOLF.get(), RatWolfRenderer::new);
    }
}
