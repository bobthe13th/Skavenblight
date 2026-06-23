package org.ratden.skavenblight.event;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.command.SkavenDebugCommand;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.RatWolf;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.ratden.skavenblight.capability.ModCapabilities;
import org.ratden.skavenblight.block.entity.ModBlockEntities;


@EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModEvents {

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        SkavenDebugCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onAttributeCreate(EntityAttributeCreationEvent event) {
        event.put(ModEntities.RAT_WOLF.get(), RatWolf.createAttributes().build());
    }

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Register Warp Flux for the Nexus
        event.registerBlockEntity(
                ModCapabilities.WARP_FLUX,
                ModBlockEntities.WARPSTONE_NEXUS.get(),
                (blockEntity, side) -> blockEntity.getFluxStorage()
        );
    }
}