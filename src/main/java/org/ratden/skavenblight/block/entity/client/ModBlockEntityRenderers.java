package org.ratden.skavenblight.block.entity.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.entity.ModBlockEntities;

/**
 * Registers client renderers for Skavenblight BlockEntityTypes.
 *
 * This class is restricted to the physical client because block-entity
 * renderers depend on Minecraft client code.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class ModBlockEntityRenderers {

    @SubscribeEvent
    public static void registerBlockEntityRenderers(
            EntityRenderersEvent.RegisterRenderers event
    ) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.PISTON_SPIKE_TRAP.get(),
                context -> new PistonSpikeTrapRenderer()
        );
    }

    private ModBlockEntityRenderers() {
    }
}