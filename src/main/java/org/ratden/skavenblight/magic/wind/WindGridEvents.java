package org.ratden.skavenblight.magic.wind;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.ratden.skavenblight.Skavenblight;

/**
 * Handles runtime events belonging to the wind grid system.
 */
@EventBusSubscriber(modid = Skavenblight.MODID)
public final class WindGridEvents {

    @SubscribeEvent
    public static void onChunkLoad(
            ChunkEvent.Load event
    ) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        WindGridManager.get(serverLevel)
                .markLoaded(event.getChunk().getPos());
    }

    @SubscribeEvent
    public static void onChunkUnload(
            ChunkEvent.Unload event
    ) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        WindGridManager.get(serverLevel)
                .markUnloaded(event.getChunk().getPos());
    }

    @SubscribeEvent
    public static void onLevelTick(
            LevelTickEvent.Post event
    ) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        WindGridManager.get(serverLevel).tick(serverLevel);
    }

    private WindGridEvents() {
    }
}
