package org.ratden.skavenblight.capability;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.block.entity.ModBlockEntities;

/**
 * Attaches capability providers to Skavenblight game objects.
 *
 * ModCapabilities declares custom capability tokens. This class defines
 * which registered block-entity types expose those capabilities and how
 * their implementations are retrieved.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD
)
public final class ModCapabilityProviders {

    @SubscribeEvent
    public static void registerCapabilityProviders(
            RegisterCapabilitiesEvent event
    ) {
        event.registerBlockEntity(
                ModCapabilities.WARP_FLUX,
                ModBlockEntities.WARP_FLUX_STORAGE.get(),
                (blockEntity, side) ->
                        blockEntity.getFluxStorage()
        );

        event.registerBlockEntity(
                ModCapabilities.WARP_FLUX,
                ModBlockEntities.WARPSTONE_NEXUS.get(),
                (blockEntity, side) ->
                        blockEntity.getFluxStorage()
        );

        event.registerBlockEntity(
                ModCapabilities.WARP_FLUX,
                ModBlockEntities.WARP_FLUX_FURNACE.get(),
                (blockEntity, side) ->
                        blockEntity.getFluxStorage()
        );

        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.WARP_FLUX_FURNACE.get(),
                (blockEntity, side) ->
                        blockEntity.getItemHandler(side)
        );

        event.registerBlockEntity(
                ModCapabilities.WARP_FLUX,
                ModBlockEntities.PISTON_SPIKE_TRAP.get(),
                (blockEntity, side) ->
                        blockEntity.getFluxStorage()
        );
    }

    private ModCapabilityProviders() {
    }
}