package org.ratden.skavenblight.event;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.command.SkavenDebugCommand;
import org.ratden.skavenblight.entity.ModEntities;
import org.ratden.skavenblight.entity.custom.WolfRat;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.capabilities.Capabilities; // Added for ItemHandler capability
import org.ratden.skavenblight.capability.ModCapabilities;
import org.ratden.skavenblight.block.entity.ModBlockEntities;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.ratden.skavenblight.screen.ModMenus;
import org.ratden.skavenblight.screen.WarpFluxFurnaceScreen;
import org.ratden.skavenblight.block.entity.WarpFluxFurnaceBlockEntity; // Added for casting in capabilities

@EventBusSubscriber(modid = Skavenblight.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModEvents {

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        SkavenDebugCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onAttributeCreate(EntityAttributeCreationEvent event) {
        event.put(ModEntities.RAT_WOLF.get(), WolfRat.createAttributes().build());
    }

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Register Warp Flux for the Nexus
        event.registerBlockEntity(
                ModCapabilities.WARP_FLUX,
                ModBlockEntities.WARPSTONE_NEXUS.get(),
                (blockEntity, side) -> blockEntity.getFluxStorage()
        );

        // --- NEW: Register Capabilities for the Warp Flux Furnace ---

        // 1. Expose Warp Flux so conduits can power it
        event.registerBlockEntity(
                ModCapabilities.WARP_FLUX,
                ModBlockEntities.WARP_FLUX_FURNACE.get(),
                (blockEntity, side) -> {
                    if (blockEntity instanceof WarpFluxFurnaceBlockEntity furnace) {
                        return furnace.getFluxStorage();
                    }
                    return null;
                }
        );

        // 2. Expose the Item Handler so hoppers/pipes can move items in and out
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.WARP_FLUX_FURNACE.get(),
                (blockEntity, side) -> {
                    if (blockEntity instanceof WarpFluxFurnaceBlockEntity furnace) {
                        return furnace.getItemHandler(side); // only output out of hopper
                    }
                    return null;
                }
        );
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.WARP_FLUX_FURNACE_MENU.get(), WarpFluxFurnaceScreen::new);
    }
}