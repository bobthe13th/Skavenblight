package org.ratden.skavenblight.command;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.ratden.skavenblight.Skavenblight;

/**
 * Registers Skavenblight commands on the game event bus.
 *
 * The GAME bus is the default bus for EventBusSubscriber in the current
 * loader version.
 */
@EventBusSubscriber(
        modid = Skavenblight.MODID
)
public final class ModCommandEvents {

    @SubscribeEvent
    public static void registerCommands(
            RegisterCommandsEvent event
    ) {
        SkavenDebugCommand.register(
                event.getDispatcher()
        );
    }

    private ModCommandEvents() {
    }
}