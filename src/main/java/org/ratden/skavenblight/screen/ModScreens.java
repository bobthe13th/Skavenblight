package org.ratden.skavenblight.screen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import org.ratden.skavenblight.Skavenblight;

/**
 * Connects registered menu types to their client-side screen classes.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID,
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class ModScreens {

    @SubscribeEvent
    public static void registerScreens(
            RegisterMenuScreensEvent event
    ) {
        event.register(
                ModMenus.WARP_FLUX_FURNACE_MENU.get(),
                WarpFluxFurnaceScreen::new
        );

        event.register(
                ModMenus.RESEARCH_TABLE_MENU.get(),
                org.ratden.skavenblight.screen.ResearchTableScreen::new
        );
    }

    private ModScreens() {
    }
}