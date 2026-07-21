package org.ratden.skavenblight.event;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.client.RatWolfRenderer;

// Import your new block entity and renderer:
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import org.ratden.skavenblight.block.entity.client.PistonSpikeTrapRenderer;

@EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModClientEvents {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Your existing Rat Wolf
        event.registerEntityRenderer(ModEntities.RAT_WOLF.get(), RatWolfRenderer::new);

        event.registerBlockEntityRenderer(ModBlockEntities.PISTON_SPIKE_TRAP.get(),
                context -> new PistonSpikeTrapRenderer());
        event.registerBlockEntityRenderer(org.ratden.skavenblight.block.entity.ModBlockEntities.WARP_LIGHTNING_COIL_BE.get(),
                org.ratden.skavenblight.block.entity.client.WarpLightningCoilRenderer::new);
    }
}