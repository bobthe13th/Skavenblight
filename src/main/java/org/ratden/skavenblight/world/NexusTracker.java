package org.ratden.skavenblight.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import org.ratden.skavenblight.block.entity.WarpstoneNexusEntity;

public class NexusTracker {

    private static SkavenblightWorldData getData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(
                        SkavenblightWorldData::new,
                        SkavenblightWorldData::load
                ),
                SkavenblightWorldData.DATA_NAME
        );
    }

    public static boolean hasActiveNexus(ServerLevel level) {
        return getData(level).hasActiveNexus();
    }

    public static BlockPos getActiveNexusPos(ServerLevel level) {
        return getData(level).getActiveNexusPos();
    }

    public static void setActiveNexus(ServerLevel level, BlockPos pos) {
        getData(level).setActiveNexus(pos);
    }

    public static void clearActiveNexus(ServerLevel level) {
        getData(level).clearActiveNexus();
    }

    public static boolean isActiveNexus(ServerLevel level, BlockPos pos) {
        return getData(level).isActiveNexus(pos);
    }

    public static WarpstoneNexusEntity getActiveNexusEntity(ServerLevel level) {
        if (!hasActiveNexus(level)) {
            return null;
        }

        BlockEntity blockEntity = level.getBlockEntity(getActiveNexusPos(level));

        if (blockEntity instanceof WarpstoneNexusEntity nexus) {
            return nexus;
        }

        return null;
    }
}