package org.ratden.skavenblight.screen;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.ratden.skavenblight.Skavenblight;

/**
 * Registers the MenuTypes added by Skavenblight.
 *
 * MenuTypes are common-side objects used by both the server and client.
 * Their corresponding client screen classes are connected separately by
 * ModScreens.
 */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(
                    Registries.MENU,
                    Skavenblight.MODID
            );

    public static final DeferredHolder<
            MenuType<?>,
            MenuType<WarpFluxFurnaceMenu>
            > WARP_FLUX_FURNACE_MENU =
            MENUS.register(
                    "warp_flux_furnace_menu",
                    () -> IMenuTypeExtension.create(
                            WarpFluxFurnaceMenu::new
                    )
            );

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }

    private ModMenus() {
    }
}