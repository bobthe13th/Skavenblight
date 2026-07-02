package org.ratden.skavenblight.screen; // Update this package to match your structure

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

import java.util.function.Supplier;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Skavenblight.MODID);

    public static final Supplier<MenuType<WarpFluxFurnaceMenu>> WARP_FLUX_FURNACE_MENU =
            MENUS.register("warp_flux_furnace_menu", () -> IMenuTypeExtension.create(WarpFluxFurnaceMenu::new));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}