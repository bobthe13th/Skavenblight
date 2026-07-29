package org.ratden.skavenblight.magic.wind;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.ratden.skavenblight.Skavenblight;
import org.ratden.skavenblight.magic.Wind;

/**
 * Maintains ChunkWindState's tagged-block counters incrementally (+1 on place, -1 on break) rather
 * than ever rescanning a chunk - the design doc is explicit that this is the perf-safe approach.
 * Listens on the default game event bus so it fires for ANY block carrying a wind_source tag,
 * including plain vanilla blocks, not just ones this mod defines (see WarpFluxConduitBlock's
 * onPlace/onRemove for the alternative per-block-class pattern this deliberately does NOT use).
 */
@EventBusSubscriber(modid = Skavenblight.MODID)
public class TaggedBlockWindHandler {

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        adjustTaggedCounts(serverLevel, event.getPos(), event.getPlacedBlock(), 1);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        adjustTaggedCounts(serverLevel, event.getPos(), event.getState(), -1);
    }

    private static void adjustTaggedCounts(ServerLevel level, BlockPos pos, BlockState state, int delta) {
        ChunkWindState windState = WindGridManager.get(level).getOrCreate(new ChunkPos(pos));
        for (Wind wind : Wind.values()) {
            if (state.is(ModWindBlockTags.windSource(wind))) {
                windState.addTaggedBlockCount(wind, delta);
            }
        }
    }
}
